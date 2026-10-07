# One distribution on one domain:
#   default   -> SPA bucket (OAC), app routes rewritten to /index.html
#   /api/*    -> Lambda function URL (OAC, SigV4), no caching
#   /media/*  -> media bucket (OAC), optional, prefix stripped before reaching S3
locals {
  spa_origin_id   = "spa"
  api_origin_id   = "api"
  media_origin_id = "media"
  api_domain      = trimsuffix(trimprefix(var.api_function_url, "https://"), "/")
  media_enabled   = var.media_bucket_regional_domain_name != null
  custom_domain   = var.acm_certificate_arn != null && length(var.aliases) > 0
}

data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# Forwards every viewer header, cookie and query string except Host, which must be the
# function URL's own host name for SigV4 to verify.
data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

data "aws_cloudfront_response_headers_policy" "security_headers" {
  name = "Managed-SecurityHeadersPolicy"
}

resource "aws_cloudfront_origin_access_control" "s3" {
  name                              = "${var.name}-s3"
  description                       = "Signed S3 access for ${var.name}"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_origin_access_control" "lambda" {
  name                              = "${var.name}-lambda"
  description                       = "Signed Lambda function URL access for ${var.name}"
  origin_access_control_origin_type = "lambda"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_function" "spa_routes" {
  name    = "${var.name}-spa-routes"
  runtime = "cloudfront-js-2.0"
  comment = "Serve index.html for app routes such as /albums/123"
  publish = true
  code    = <<-JS
    function handler(event) {
      var request = event.request;
      var last = request.uri.split('/').pop();
      if (last.indexOf('.') === -1) {
        request.uri = '/index.html';
      }
      return request;
    }
  JS
}

resource "aws_cloudfront_function" "media_prefix" {
  count   = local.media_enabled ? 1 : 0
  name    = "${var.name}-media-prefix"
  runtime = "cloudfront-js-2.0"
  comment = "Map /media/<key> to the S3 object <key>"
  publish = true
  code    = <<-JS
    function handler(event) {
      var request = event.request;
      request.uri = request.uri.replace(/^\/media/, '') || '/';
      return request;
    }
  JS
}

resource "aws_cloudfront_distribution" "this" {
  enabled             = true
  comment             = var.name
  is_ipv6_enabled     = true
  http_version        = "http2and3"
  price_class         = var.price_class
  default_root_object = "index.html"
  aliases             = local.custom_domain ? var.aliases : []

  origin {
    origin_id                = local.spa_origin_id
    domain_name              = var.spa_bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  origin {
    origin_id                = local.api_origin_id
    domain_name              = local.api_domain
    origin_access_control_id = aws_cloudfront_origin_access_control.lambda.id

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
      origin_read_timeout    = var.api_origin_read_timeout_seconds
    }
  }

  dynamic "origin" {
    for_each = local.media_enabled ? [var.media_bucket_regional_domain_name] : []
    content {
      origin_id                = local.media_origin_id
      domain_name              = origin.value
      origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
    }
  }

  default_cache_behavior {
    target_origin_id           = local.spa_origin_id
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD", "OPTIONS"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_optimized.id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.security_headers.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_routes.arn
    }
  }

  ordered_cache_behavior {
    path_pattern             = "/api/*"
    target_origin_id         = local.api_origin_id
    viewer_protocol_policy   = "https-only"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    compress                 = true
    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
  }

  dynamic "ordered_cache_behavior" {
    for_each = local.media_enabled ? [1] : []
    content {
      path_pattern           = "/media/*"
      target_origin_id       = local.media_origin_id
      viewer_protocol_policy = "redirect-to-https"
      allowed_methods        = ["GET", "HEAD"]
      cached_methods         = ["GET", "HEAD"]
      compress               = true
      cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id
      # Signature query parameters are checked at the edge and kept out of the cache key.
      trusted_key_groups = length(var.media_trusted_key_group_ids) > 0 ? var.media_trusted_key_group_ids : null

      function_association {
        event_type   = "viewer-request"
        function_arn = aws_cloudfront_function.media_prefix[0].arn
      }
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = local.custom_domain ? null : true
    acm_certificate_arn            = local.custom_domain ? var.acm_certificate_arn : null
    ssl_support_method             = local.custom_domain ? "sni-only" : null
    minimum_protocol_version       = local.custom_domain ? "TLSv1.2_2021" : null
  }
}
