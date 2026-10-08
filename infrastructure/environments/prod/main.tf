data "aws_caller_identity" "current" {}

locals {
  project     = "photo-album"
  environment = "prod"
  region      = "eu-north-1"

  lambda = {
    "photo-album-prod-api" = {
      source_image     = "ghcr.io/samitkumarpatel/aws-lambda-fullstack:latest"
      source_image_tag = "latest"
      architecture     = "x86_64"
      memory           = 2048
      timeout          = 30
      public_url       = true
      force_delete     = false
      environment = {
        PHOTO_ALBUM_DATA_MODE       = "dynamodb"
        PHOTO_ALBUM_TABLE           = "photo-album-prod"
        PHOTO_ALBUM_STORAGE_MODE    = "s3"
        PHOTO_ALBUM_S3_BUCKET       = "photo-album-prod-media-${data.aws_caller_identity.current.account_id}"
        PHOTO_ALBUM_S3_PREFIX       = "photo-album"
        PHOTO_ALBUM_PROCESSING_MODE = "events"
      }
    }
    "photo-album-prod-worker" = {
      source_image     = "ghcr.io/samitkumarpatel/aws-lambda-fullstack:latest"
      source_image_tag = "latest"
      architecture     = "x86_64"
      handler          = "net.samitkumar.photo_album_aws.processing.S3EventWorkerHandler::handleRequest"
      memory           = 2048
      timeout          = 120
      sqs_trigger      = "photo-album-prod-processing"
      force_delete     = false
      environment = {
        PHOTO_ALBUM_DATA_MODE       = "dynamodb"
        PHOTO_ALBUM_TABLE           = "photo-album-prod"
        PHOTO_ALBUM_STORAGE_MODE    = "s3"
        PHOTO_ALBUM_S3_BUCKET       = "photo-album-prod-media-${data.aws_caller_identity.current.account_id}"
        PHOTO_ALBUM_S3_PREFIX       = "photo-album"
        PHOTO_ALBUM_PROCESSING_MODE = "events"
        JAVA_TOOL_OPTIONS           = "--enable-native-access=ALL-UNNAMED"
      }
    }
  }

  dynamodb = {
    "photo-album-prod" = {
      hash_key            = "pk"
      range_key           = "sk"
      indexes             = { gsi1 = { hash_key = "gsi1pk", range_key = "gsi1sk" } }
      ttl_attribute       = "ttl"
      deletion_protection = true
    }
  }

  s3 = ["photo-album-prod-media-${data.aws_caller_identity.current.account_id}"]
}

module "photo_album" {
  source = "../../stacks/stack/1.0.0"

  name     = "photo-album-prod"
  lambda   = local.lambda
  dynamodb = local.dynamodb
  s3       = local.s3
  sqs = [
    {
      name      = "photo-album-prod-processing"
      s3_bucket = "photo-album-prod-media-${data.aws_caller_identity.current.account_id}"
      s3_prefix = "photo-album/originals/"
    },
  ]
}
