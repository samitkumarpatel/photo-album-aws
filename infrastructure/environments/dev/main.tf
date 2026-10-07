# Dev environment: every value is spelled out here, so there are no .tfvars files.
# To add an environment, copy this folder and change the literals.
locals {
  project     = "photo-album"
  environment = "dev"
  region      = "eu-north-1" # matches the application's default region
}

module "photo_album" {
  source = "../../stacks/photo-album"

  project     = local.project
  environment = local.environment

  # API container image. Push it to the stack's ECR repository before applying (see
  # infrastructure/README.md). Bump the tag for every release; Lambda redeploys when it changes.
  # The command matches the image's CMD; the worker function overrides it with its own handler.
  api_image_tag            = "0.0.1"
  api_image_command        = ["net.samitkumar.photo_album_aws.lambda.StreamLambdaHandler::handleRequest"]
  api_image_immutable_tags = true
  api_image_keep_count     = 10

  lambda_architecture    = "arm64"
  lambda_memory_mb       = 2048
  lambda_timeout_seconds = 30
  log_retention_days     = 14

  # Upload processing worker (same image, its own handler).
  worker_memory_mb           = 2048
  worker_timeout_seconds     = 120
  worker_maximum_concurrency = 2

  # Data: cheap to recreate in dev.
  table_point_in_time_recovery = false
  table_deletion_protection    = false

  # Media
  media_prefix          = "photo-album"
  media_versioning      = false
  force_destroy_buckets = true
  # Two steps without a custom domain: apply with the route on, then set media_cdn_domain to the
  # cloudfront_domain_name output and apply again. Until then the API hands out S3 presigned URLs.
  enable_media_route = true
  media_cdn_domain   = null

  # Delivery: no custom domain in dev; the site is served on the CloudFront domain.
  domain_aliases      = []
  acm_certificate_arn = null
  price_class         = "PriceClass_100"

  # Cost and dead-letter alerts. Replace with a real address before applying;
  # each address must confirm its SNS subscription by email.
  budget_monthly_limit_usd = 10
  budget_alert_emails      = ["alerts@example.com"]
}
