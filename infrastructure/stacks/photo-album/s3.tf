# Private buckets. S3 already encrypts new objects (SSE-S3) and disables ACLs by default.
resource "aws_s3_bucket" "this" {
  for_each = toset(var.s3_buckets)

  bucket = each.key
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each = aws_s3_bucket.this

  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Browsers upload and download with presigned URLs, which carry their own authorization.
resource "aws_s3_bucket_cors_configuration" "this" {
  for_each = aws_s3_bucket.this

  bucket = each.value.id

  cors_rule {
    allowed_methods = ["PUT", "GET", "HEAD"]
    allowed_origins = ["*"]
    allowed_headers = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  for_each = aws_s3_bucket.this

  bucket = each.value.id

  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"
    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

# ObjectCreated events to the queues that ask for them (one notification resource per bucket).
resource "aws_s3_bucket_notification" "this" {
  for_each = toset([for q in var.sqs : q.s3_bucket if q.s3_bucket != null])

  bucket = aws_s3_bucket.this[each.key].id

  dynamic "queue" {
    for_each = [for q in var.sqs : q if q.s3_bucket == each.key]
    content {
      queue_arn     = aws_sqs_queue.this[queue.value.name].arn
      events        = ["s3:ObjectCreated:*"]
      filter_prefix = queue.value.s3_prefix
    }
  }

  # S3 checks it may send to the queue when the notification is created.
  depends_on = [aws_sqs_queue_policy.s3]
}
