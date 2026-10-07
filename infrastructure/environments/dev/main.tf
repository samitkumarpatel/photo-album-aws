# Dev environment: every value is spelled out here, so there are no .tfvars files.
# To add an environment, copy this folder and change the names.
data "aws_caller_identity" "current" {}

locals {
  project     = "photo-album" # used for tags (providers.tf)
  environment = "dev"
  region      = "eu-north-1" # matches the application's default region

  # Copied into each function's ECR repository when the function is first created.
  # Afterwards the pipeline pushes new images and updates the functions.
  bootstrap_image = "ghcr.io/samitkumarpatel/photo-album-aws:latest"

  table  = "photo-album-dev"
  bucket = "photo-album-dev-media-${data.aws_caller_identity.current.account_id}" # bucket names are global
  queue  = "photo-album-dev-processing"

  # What the application reads; see src/main/resources/application.yaml.
  app_environment = {
    PHOTO_ALBUM_DATA_MODE       = "dynamodb"
    PHOTO_ALBUM_TABLE           = local.table
    PHOTO_ALBUM_STORAGE_MODE    = "s3"
    PHOTO_ALBUM_S3_BUCKET       = local.bucket
    PHOTO_ALBUM_S3_PREFIX       = "photo-album"
    PHOTO_ALBUM_PROCESSING_MODE = "events" # uploads are processed by the worker, not the API
  }
}

module "photo_album" {
  source = "../../stacks/photo-album"

  name = "photo-album-dev"

  functions = {
    "photo-album-dev-api" = {
      image       = local.bootstrap_image # its CMD already runs StreamLambdaHandler
      memory      = 2048
      timeout     = 30
      public_url  = true
      environment = local.app_environment
    }

    "photo-album-dev-worker" = {
      image       = local.bootstrap_image
      handler     = "net.samitkumar.photo_album_aws.processing.S3EventWorkerHandler::handleRequest"
      memory      = 2048
      timeout     = 120
      sqs_trigger = local.queue
      environment = merge(local.app_environment, {
        JAVA_TOOL_OPTIONS = "--enable-native-access=ALL-UNNAMED" # native WebP encoder
      })
    }
  }

  s3_buckets = [local.bucket]

  dynamodb = {
    (local.table) = {
      hash_key      = "pk"
      range_key     = "sk"
      indexes       = { gsi1 = { hash_key = "gsi1pk", range_key = "gsi1sk" } }
      ttl_attribute = "ttl" # share links expire on their own
    }
  }

  sqs = [
    # New originals are queued for the worker.
    { name = local.queue, s3_bucket = local.bucket, s3_prefix = "photo-album/originals/" },
  ]
}
