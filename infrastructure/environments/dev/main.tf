data "aws_caller_identity" "current" {}

locals {
  project          = "photo-album"
  environment      = "dev"
  region           = "eu-north-1"
  media_cdn_domain = "d38gufr7jg1s4v.cloudfront.net"

  # Placeholder: replace with a domain you own. The website is served at the apex and www, the API at api.
  domain = "your-task.dev"

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
        PHOTO_ALBUM_DATA_MODE               = "dynamodb"
        PHOTO_ALBUM_TABLE                   = "photo-album-dev"
        PHOTO_ALBUM_STORAGE_MODE            = "s3"
        PHOTO_ALBUM_S3_BUCKET               = "photo-album-dev-media-${data.aws_caller_identity.current.account_id}"
        PHOTO_ALBUM_S3_PREFIX               = "photo-album"
        PHOTO_ALBUM_PROCESSING_MODE         = "events"
        PHOTO_ALBUM_CDN_DOMAIN              = local.media_cdn_domain
        PHOTO_ALBUM_CDN_KEY_PAIR_ID         = aws_cloudfront_public_key.media.id
        PHOTO_ALBUM_CDN_SIGNING_KMS_KEY_ARN = aws_kms_key.media_signing.arn
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

resource "aws_kms_key" "media_signing" {
  description              = "Signs CloudFront URLs for private photo media"
  customer_master_key_spec = "RSA_2048"
  key_usage                = "SIGN_VERIFY"
  deletion_window_in_days  = 7
}

data "aws_kms_public_key" "media_signing" {
  key_id = aws_kms_key.media_signing.key_id
}

resource "aws_cloudfront_public_key" "media" {
  name        = "photo-album-dev-media-signing"
  comment     = "Verifies private CloudFront photo URLs signed by AWS KMS"
  encoded_key = data.aws_kms_public_key.media_signing.public_key_pem
}

resource "aws_cloudfront_key_group" "media" {
  name    = "photo-album-dev-media-signing"
  comment = "Trusted signer for private photo media URLs"
  items   = [aws_cloudfront_public_key.media.id]
}

module "photo_album" {
  source = "../../stacks/backend/1.0.0"

  name                   = "photo-album-dev"
  lambda                 = local.lambda
  dynamodb               = local.dynamodb
  s3                     = local.s3
  api_cors_allow_origins = ["https://d38gufr7jg1s4v.cloudfront.net", "https://${local.domain}", "https://www.${local.domain}"]
  api_enable_access_logs = true
  api_domain_name        = "api.${local.domain}"
  api_route53_zone_id    = module.route53.zone_id
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

  name               = each.value
  environment        = local.environment
  api_url            = module.photo_album.api_url
  media_bucket_name  = one(module.photo_album.s3_buckets)
  media_key_group_id = aws_cloudfront_key_group.media.id

  # Creates the us-east-1 certificate and CloudFront aliases; the route53 module owns the A/AAAA records.
  domain_name              = local.domain
  alternative_domain_names = ["www.${local.domain}"]
  route53_zone_id          = module.route53.zone_id
  create_alias_records     = false

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }
}

module "route53" {
  source = "../../stacks/route53/1.0.0"

  dns = local.domain
  records = {
    "@" = module.frontend["photo-album"].dns_alias
    www = module.frontend["photo-album"].dns_alias
    api = module.photo_album.dns_alias
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
