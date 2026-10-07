# Bucket policy: only the given CloudFront distribution (through origin access control) may
# read objects, and every request must use HTTPS. Identity-based access, such as the Lambda
# role, is granted through IAM and is not affected.
data "aws_iam_policy_document" "this" {
  statement {
    sid       = "AllowCloudFrontRead"
    actions   = ["s3:GetObject"]
    resources = ["${var.bucket_arn}/${var.object_prefix}*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [var.distribution_arn]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [var.bucket_arn, "${var.bucket_arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  bucket = var.bucket_id
  policy = data.aws_iam_policy_document.this.json
}
