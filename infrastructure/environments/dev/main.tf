data "aws_caller_identity" "current" {}

locals {
  project     = "photo-album"
  environment = "dev"
  region      = "eu-north-1"

  lambda = {
    "photo-album-dev-api" = {
      architecture = "x86_64"
      memory       = 2048
      timeout      = 30
      http_router = {
        "/api"      = "/api"
        "/actuator" = "/actuator"
      }
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
      architecture = "x86_64"
      handler      = "net.samitkumar.photo_album_aws.processing.S3EventWorkerHandler::handleRequest"
      memory       = 2048
      timeout      = 120
      sqs_trigger  = "photo-album-dev-processing"
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

  name                   = "photo-album-dev"
  lambda                 = local.lambda
  dynamodb               = local.dynamodb
  s3                     = local.s3
  api_cors_allow_origins = ["https://d38gufr7jg1s4v.cloudfront.net"]
  api_enable_access_logs = true
  sqs = [
    {
      name      = "photo-album-dev-processing"
      s3_bucket = "photo-album-dev-media-${data.aws_caller_identity.current.account_id}"
      s3_prefix = "photo-album/originals/"
    },
  ]
}

module "frontend" {
  source = "../../stacks/frontend/1.0.0"
  for_each = toset([
    "photo-album",
  ])

  name        = each.value
  environment = local.environment
  api_url     = module.photo_album.api_url

  # Set both values to enable an ACM certificate, DNS validation, and a custom domain.
  domain_name     = null
  route53_zone_id = null

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }
}

module "github_oidc" {
  source = "../../stacks/github-actions/1.0.0"
  for_each = toset([
    "samitkumarpatel/photo-album-aws",
  ])

  name                 = replace(each.value, "/", "_")
  repos                = [each.value]
  environments         = ["dev"]
  create_oidc_provider = each.value == "samitkumarpatel/photo-album-aws"
}
