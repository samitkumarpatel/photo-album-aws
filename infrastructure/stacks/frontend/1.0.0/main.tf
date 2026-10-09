data "aws_caller_identity" "current" {}

locals {
  bucket_name     = "${var.name}-${var.environment}-${data.aws_caller_identity.current.account_id}-frontend"
  api_domain_name = trimsuffix(trimprefix(var.api_url, "https://"), "/")
  has_domain      = var.domain_name != null
  has_media       = var.media_bucket_name != null
  common_tags     = merge(var.tags, { Site = var.name, Environment = var.environment })
}

data "aws_s3_bucket" "media" {
  count  = local.has_media ? 1 : 0
  bucket = var.media_bucket_name
}

resource "aws_s3_bucket" "frontend" {
  bucket = local.bucket_name
  tags   = local.common_tags
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket                  = aws_s3_bucket.frontend.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  versioning_configuration {
    status = var.enable_versioning ? "Enabled" : "Suspended"
  }
}

resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = "${var.name}-${var.environment}-frontend-oac"
  description                       = "Origin access control for ${var.name} ${var.environment} frontend"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_origin_access_control" "media" {
  count = local.has_media ? 1 : 0

  name                              = "${var.name}-${var.environment}-media-oac"
  description                       = "Origin access control for ${var.name} ${var.environment} media"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_function" "spa_router" {
  name    = "${var.name}-${var.environment}-spa-router"
  runtime = "cloudfront-js-2.0"
  comment = "Serve index.html for frontend client-side routes"
  publish = true

  code = <<-JS
    function handler(event) {
      var request = event.request;
      var path = request.uri;
      var lastSegment = path.substring(path.lastIndexOf('/') + 1);
      if (lastSegment.indexOf('.') === -1) {
        request.uri = '/index.html';
      }
      return request;
    }
  JS
}

resource "aws_cloudfront_distribution" "frontend" {
  enabled             = true
  comment             = "${var.name} ${var.environment} frontend"
  default_root_object = "index.html"
  aliases             = local.has_domain ? [var.domain_name] : []
  price_class         = var.price_class
  wait_for_deployment = true
  tags                = local.common_tags

  origin {
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_id                = "s3-${aws_s3_bucket.frontend.id}"
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  origin {
    domain_name = local.api_domain_name
    origin_id   = "api-gateway-${var.name}-${var.environment}"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  dynamic "origin" {
    for_each = local.has_media ? [1] : []
    content {
      domain_name              = data.aws_s3_bucket.media[0].bucket_regional_domain_name
      origin_id                = "media-${var.media_bucket_name}"
      origin_access_control_id = aws_cloudfront_origin_access_control.media[0].id
    }
  }

  default_cache_behavior {
    target_origin_id       = "s3-${aws_s3_bucket.frontend.id}"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD", "OPTIONS"]
    compress               = true
    cache_policy_id        = "658327ea-f89d-4fab-a63d-7e88639e58f6" # AWS Managed-CachingOptimized

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_router.arn
    }
  }

  dynamic "ordered_cache_behavior" {
    for_each = ["/api", "/api/*", "/actuator", "/actuator/*"]
    content {
      path_pattern             = ordered_cache_behavior.value
      target_origin_id         = "api-gateway-${var.name}-${var.environment}"
      viewer_protocol_policy   = "redirect-to-https"
      allowed_methods          = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
      cached_methods           = ["GET", "HEAD", "OPTIONS"]
      compress                 = true
      cache_policy_id          = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad" # AWS Managed-CachingDisabled
      origin_request_policy_id = "b689b0a8-53d0-40ab-baf2-68738e2966ac" # AWS Managed-AllViewerExceptHostHeader
    }
  }

  dynamic "ordered_cache_behavior" {
    for_each = local.has_media ? ["${var.media_path_prefix}/*"] : []
    content {
      path_pattern           = ordered_cache_behavior.value
      target_origin_id       = "media-${var.media_bucket_name}"
      viewer_protocol_policy = "redirect-to-https"
      allowed_methods        = ["GET", "HEAD", "OPTIONS"]
      cached_methods         = ["GET", "HEAD"]
      compress               = true
      cache_policy_id        = "658327ea-f89d-4fab-a63d-7e88639e58f6" # AWS Managed-CachingOptimized
      trusted_key_groups     = [var.media_key_group_id]
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  dynamic "viewer_certificate" {
    for_each = local.has_domain ? [] : [1]
    content {
      cloudfront_default_certificate = true
    }
  }

  dynamic "viewer_certificate" {
    for_each = local.has_domain ? [1] : []
    content {
      acm_certificate_arn      = aws_acm_certificate_validation.frontend[0].certificate_arn
      ssl_support_method       = "sni-only"
      minimum_protocol_version = "TLSv1.2_2021"
    }
  }

  depends_on = [aws_s3_bucket_public_access_block.frontend]
}

resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  policy = data.aws_iam_policy_document.frontend_bucket.json
}

resource "aws_s3_bucket_policy" "media" {
  count  = local.has_media ? 1 : 0
  bucket = var.media_bucket_name
  policy = data.aws_iam_policy_document.media_bucket[0].json
}

data "aws_iam_policy_document" "media_bucket" {
  count = local.has_media ? 1 : 0

  statement {
    sid       = "AllowCloudFrontRead"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${data.aws_s3_bucket.media[0].arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.frontend.arn]
    }
  }
}

data "aws_iam_policy_document" "frontend_bucket" {
  statement {
    sid       = "AllowCloudFrontRead"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.frontend.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.frontend.arn]
    }
  }
}

resource "aws_acm_certificate" "frontend" {
  count    = local.has_domain ? 1 : 0
  provider = aws.us_east_1

  domain_name       = var.domain_name
  validation_method = "DNS"
  tags              = local.common_tags

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "certificate_validation" {
  for_each = local.has_domain ? {
    for option in aws_acm_certificate.frontend[0].domain_validation_options : option.domain_name => {
      name   = option.resource_record_name
      record = option.resource_record_value
      type   = option.resource_record_type
    }
  } : {}

  zone_id         = var.route53_zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "frontend" {
  count    = local.has_domain ? 1 : 0
  provider = aws.us_east_1

  certificate_arn         = aws_acm_certificate.frontend[0].arn
  validation_record_fqdns = [for record in aws_route53_record.certificate_validation : record.fqdn]
}

resource "aws_route53_record" "frontend_a" {
  count   = local.has_domain ? 1 : 0
  zone_id = var.route53_zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.frontend.domain_name
    zone_id                = aws_cloudfront_distribution.frontend.hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "frontend_aaaa" {
  count   = local.has_domain ? 1 : 0
  zone_id = var.route53_zone_id
  name    = var.domain_name
  type    = "AAAA"

  alias {
    name                   = aws_cloudfront_distribution.frontend.domain_name
    zone_id                = aws_cloudfront_distribution.frontend.hosted_zone_id
    evaluate_target_health = false
  }
}
