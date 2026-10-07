# The whole photo album application: data, media, API, processing worker, CDN and alerts.
# Environments call this stack once and pass plain values; it holds no environment literals.
data "aws_caller_identity" "current" {}

locals {
  prefix = "${var.project}-${var.environment}"
  # Bucket names are global, so the account id keeps them unique.
  account_id = data.aws_caller_identity.current.account_id
  # The app stores media under this prefix today (spring.application.storage.s3.prefix).
  media_object_prefix = var.media_prefix == "" ? "" : "${trimsuffix(var.media_prefix, "/")}/"
  site_origins        = distinct(concat([module.site.url], [for alias in var.domain_aliases : "https://${alias}"]))
}

module "data_table" {
  source = "../../modules/dynamodb-table"

  name                   = "${local.prefix}-data"
  point_in_time_recovery = var.table_point_in_time_recovery
  deletion_protection    = var.table_deletion_protection
}

module "media_bucket" {
  source = "../../modules/s3-bucket"

  name          = "${local.prefix}-media-${local.account_id}"
  force_destroy = var.force_destroy_buckets
  versioning    = var.media_versioning

  lifecycle_rules = concat(
    [
      { id = "abort-incomplete-uploads", prefix = "", abort_incomplete_multipart_days = 1 },
      { id = "expire-trash", prefix = "trash/", expiration_days = 30 },
      { id = "tier-originals", prefix = "originals/", transition_days = 90 },
    ],
    local.media_object_prefix == "" || local.media_object_prefix == "originals/" ? [] : [
      { id = "tier-current-media", prefix = local.media_object_prefix, transition_days = 90 },
    ],
  )

  # For the planned presigned PUT upload flow: the browser uploads straight to S3.
  cors_rules = [{
    allowed_methods = ["PUT", "GET", "HEAD"]
    allowed_origins = local.site_origins
  }]
}

module "spa_bucket" {
  source = "../../modules/s3-bucket"

  name          = "${local.prefix}-site-${local.account_id}"
  force_destroy = true # rebuilt from source on every deploy
}

module "api_repository" {
  source = "../../modules/ecr-repository"

  name           = "${local.prefix}-api"
  immutable_tags = var.api_image_immutable_tags
  keep_images    = var.api_image_keep_count
  force_delete   = var.force_destroy_buckets
}

module "api" {
  source = "../../modules/lambda-api"

  name               = "${local.prefix}-api"
  image_uri          = "${module.api_repository.url}:${var.api_image_tag}"
  architecture       = var.lambda_architecture
  memory_mb          = var.lambda_memory_mb
  timeout_seconds    = var.lambda_timeout_seconds
  log_retention_days = var.log_retention_days

  dynamodb_table_arn  = module.data_table.arn
  media_bucket_arn    = module.media_bucket.arn
  media_object_prefix = local.media_object_prefix

  # Names the application reads; see src/main/resources/application.yaml.
  environment = merge(var.lambda_extra_environment, {
    PHOTO_ALBUM_DATA_MODE    = "dynamodb"
    PHOTO_ALBUM_TABLE        = module.data_table.name
    PHOTO_ALBUM_STORAGE_MODE = "s3"
    PHOTO_ALBUM_S3_BUCKET    = module.media_bucket.id
    PHOTO_ALBUM_S3_PREFIX    = trimsuffix(var.media_prefix, "/")
    # S3 events drive the worker, so the API does not process uploads itself.
    PHOTO_ALBUM_PROCESSING_MODE = "events"
    PHOTO_ALBUM_LAMBDA_ROLE     = "api"
    # No CloudFront media route: the API hands out S3 presigned URLs.
  })
}

# Upload processing: S3 ObjectCreated under originals/ -> SQS -> worker Lambda (same native image, worker role).
resource "aws_sns_topic" "alerts" {
  name = "${local.prefix}-alerts"
}

resource "aws_sns_topic_subscription" "alert_emails" {
  for_each = toset(var.budget_alert_emails)

  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = each.value
}

module "processing_queue" {
  source = "../../modules/job-queue"

  name                       = "${local.prefix}-processing"
  visibility_timeout_seconds = var.worker_timeout_seconds * 6
  s3_source_bucket_arn       = module.media_bucket.arn
  s3_source_account_id       = local.account_id
  alarm_actions              = [aws_sns_topic.alerts.arn]
}

resource "aws_s3_bucket_notification" "media" {
  bucket = module.media_bucket.id

  queue {
    queue_arn     = module.processing_queue.arn
    events        = ["s3:ObjectCreated:*"]
    filter_prefix = "${local.media_object_prefix}originals/"
  }

  depends_on = [module.processing_queue.s3_send_policy_id]
}

module "worker" {
  source = "../../modules/lambda-worker"

  name                 = "${local.prefix}-worker"
  description          = "Photo album upload processing"
  image_uri            = "${module.api_repository.url}:${var.api_image_tag}"
  architecture         = var.lambda_architecture
  memory_mb            = var.worker_memory_mb
  timeout_seconds      = var.worker_timeout_seconds
  maximum_concurrency  = var.worker_maximum_concurrency
  log_retention_days   = var.log_retention_days
  queue_arn            = module.processing_queue.arn
  dynamodb_table_arn   = module.data_table.arn
  media_bucket_arn     = module.media_bucket.arn
  media_read_prefixes  = ["${local.media_object_prefix}originals/"]
  media_write_prefixes = ["${local.media_object_prefix}derived/"]

  environment = {
    PHOTO_ALBUM_DATA_MODE       = "dynamodb"
    PHOTO_ALBUM_TABLE           = module.data_table.name
    PHOTO_ALBUM_STORAGE_MODE    = "s3"
    PHOTO_ALBUM_S3_BUCKET       = module.media_bucket.id
    PHOTO_ALBUM_S3_PREFIX       = trimsuffix(var.media_prefix, "/")
    PHOTO_ALBUM_PROCESSING_MODE = "events"
    PHOTO_ALBUM_LAMBDA_ROLE     = "worker"
    AWS_LWA_PASS_THROUGH_PATH   = "/events"
  }
}

module "site" {
  source = "../../modules/cloudfront-site"

  # No media route: the media bucket is never an origin, so it gets no CloudFront read access either;
  # the API serves media with its own S3 presigned URLs instead.
  name                            = local.prefix
  spa_bucket_regional_domain_name = module.spa_bucket.regional_domain_name
  api_function_url                = module.api.function_url
  aliases                         = var.domain_aliases
  acm_certificate_arn             = var.acm_certificate_arn
  price_class                     = var.price_class
}

module "spa_read_policy" {
  source = "../../modules/s3-cloudfront-read-policy"

  bucket_id        = module.spa_bucket.id
  bucket_arn       = module.spa_bucket.arn
  distribution_arn = module.site.distribution_arn
}

module "api_access" {
  source = "../../modules/lambda-cloudfront-access"

  function_name    = module.api.function_name
  qualifier        = module.api.alias_name
  distribution_arn = module.site.distribution_arn
}

module "budget" {
  source = "../../modules/budget"

  name              = "${local.prefix}-monthly"
  monthly_limit_usd = var.budget_monthly_limit_usd
  alert_emails      = var.budget_alert_emails
}
