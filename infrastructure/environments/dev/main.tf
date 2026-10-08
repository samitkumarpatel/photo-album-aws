data "aws_caller_identity" "current" {}

locals {
  project     = "photo-album"
  environment = "dev"
  region      = "eu-north-1"

  lambda = {
    "photo-album-dev-api" = {
      source_image     = "ghcr.io/samitkumarpatel/aws-lambda-fullstack:latest"
      source_image_tag = "latest"
      architecture     = "x86_64"
      memory           = 2048
      timeout          = 30
      public_url       = true
      environment = {
        PHOTO_ALBUM_DATA_MODE       = "dynamodb"
        PHOTO_ALBUM_TABLE           = "photo-album-dev"
        PHOTO_ALBUM_STORAGE_MODE    = "s3"
        PHOTO_ALBUM_S3_BUCKET       = "photo-album-dev-media-${data.aws_caller_identity.current.account_id}"
        PHOTO_ALBUM_S3_PREFIX       = "photo-album"
        PHOTO_ALBUM_PROCESSING_MODE = "events"
      }
    }
    "photo-album-dev-worker" = {
      source_image     = "ghcr.io/samitkumarpatel/aws-lambda-fullstack:latest"
      source_image_tag = "latest"
      architecture     = "x86_64"
      handler          = "net.samitkumar.photo_album_aws.processing.S3EventWorkerHandler::handleRequest"
      memory           = 2048
      timeout          = 120
      sqs_trigger      = "photo-album-dev-processing"
      environment = {
        PHOTO_ALBUM_DATA_MODE       = "dynamodb"
        PHOTO_ALBUM_TABLE           = "photo-album-dev"
        PHOTO_ALBUM_STORAGE_MODE    = "s3"
        PHOTO_ALBUM_S3_BUCKET       = "photo-album-dev-media-${data.aws_caller_identity.current.account_id}"
        PHOTO_ALBUM_S3_PREFIX       = "photo-album"
        PHOTO_ALBUM_PROCESSING_MODE = "events"
        JAVA_TOOL_OPTIONS           = "--enable-native-access=ALL-UNNAMED"
      }
    }
  }

  dynamodb = {
    "photo-album-dev" = {
      hash_key            = "pk"
      range_key           = "sk"
      indexes             = { gsi1 = { hash_key = "gsi1pk", range_key = "gsi1sk" } }
      ttl_attribute       = "ttl"
      deletion_protection = false
    }
  }

  s3 = ["photo-album-dev-media-${data.aws_caller_identity.current.account_id}"]
}

module "photo_album" {
  source = "../../stacks/backend/1.0.0"

  name     = "photo-album-dev"
  lambda   = local.lambda
  dynamodb = local.dynamodb
  s3       = local.s3
  sqs = [
    {
      name      = "photo-album-dev-processing"
      s3_bucket = "photo-album-dev-media-${data.aws_caller_identity.current.account_id}"
      s3_prefix = "photo-album/originals/"
    },
  ]
}

# The GitHub OIDC provider is account-wide. Keep its ownership in one state
# only; this shared role trusts the dev and prod GitHub Environments.
module "github_actions" {
  source = "../../stacks/github-actions/1.0.0"

  name                = "photo-album-github-actions-deploy"
  repository_owner    = "samitkumarpatel"
  repository_owner_id = "7632269"
  repository_name     = "photo-album-aws"
  repository_id       = "1407945617"
  environment_names   = ["dev", "prod"]
  account_id          = data.aws_caller_identity.current.account_id
  region              = local.region
}
