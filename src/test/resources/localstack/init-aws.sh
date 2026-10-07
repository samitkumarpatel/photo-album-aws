#!/bin/bash
# LocalStack ready hook: creates the AWS resources the app expects to already exist.
#
# The application never creates buckets or tables itself. In deployed environments infrastructure
# as code owns them; locally and in tests this script stands in for it.
# Keep it idempotent: LocalStack re-runs ready.d hooks when a container is reused.
set -eu

REGION="${AWS_DEFAULT_REGION:-eu-north-1}"
BUCKET="${PHOTO_ALBUM_S3_BUCKET:-photo-album-media}"
TABLE="${PHOTO_ALBUM_TABLE:-photo-album}"

awslocal() { aws --endpoint-url=http://localhost:4566 --region "$REGION" "$@"; }

if awslocal s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1; then
  echo "[init] s3 bucket '$BUCKET' already exists"
else
  echo "[init] creating s3 bucket '$BUCKET'"
  awslocal s3api create-bucket --bucket "$BUCKET" \
    --create-bucket-configuration LocationConstraint="$REGION" >/dev/null
fi

# Browsers PUT uploads straight to presigned URLs and load media from presigned GET URLs, so the bucket must
# allow cross-origin requests. Any origin is fine locally; deployed buckets list the app's origin instead.
awslocal s3api put-bucket-cors --bucket "$BUCKET" --cors-configuration '{
  "CORSRules": [{
    "AllowedOrigins": ["*"],
    "AllowedMethods": ["PUT", "GET", "HEAD"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3600
  }]
}'

# Single table for albums, photos and share links (see DynamoDbAlbumRepository for the key design).
# No provisioned throughput: the table is on-demand (PAY_PER_REQUEST), as in production.
if awslocal dynamodb describe-table --table-name "$TABLE" >/dev/null 2>&1; then
  echo "[init] dynamodb table '$TABLE' already exists"
else
  echo "[init] creating dynamodb table '$TABLE'"
  awslocal dynamodb create-table --table-name "$TABLE" \
    --billing-mode PAY_PER_REQUEST \
    --attribute-definitions \
      AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
      AttributeName=gsi1pk,AttributeType=S AttributeName=gsi1sk,AttributeType=S \
    --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
    --global-secondary-indexes \
      'IndexName=gsi1,KeySchema=[{AttributeName=gsi1pk,KeyType=HASH},{AttributeName=gsi1sk,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
    >/dev/null
  awslocal dynamodb wait table-exists --table-name "$TABLE"
  # Expired share links are removed by DynamoDB itself.
  awslocal dynamodb update-time-to-live --table-name "$TABLE" \
    --time-to-live-specification Enabled=true,AttributeName=ttl >/dev/null
fi

# The Testcontainers wait strategy blocks on this line, so tests start only after provisioning.
echo "[init] photo-album resources ready"
