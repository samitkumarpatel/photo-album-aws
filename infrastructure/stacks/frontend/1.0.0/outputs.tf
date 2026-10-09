output "bucket_name" {
  description = "Private S3 bucket used for frontend files."
  value       = aws_s3_bucket.frontend.id
}

output "distribution_id" {
  description = "CloudFront distribution ID for cache invalidations."
  value       = aws_cloudfront_distribution.frontend.id
}

output "distribution_domain_name" {
  description = "CloudFront distribution hostname."
  value       = aws_cloudfront_distribution.frontend.domain_name
}

output "site_url" {
  description = "Public URL for the site."
  value       = local.has_domain ? "https://${var.domain_name}" : "https://${aws_cloudfront_distribution.frontend.domain_name}"
}

output "certificate_arn" {
  description = "ACM certificate ARN when a custom domain is configured."
  value       = local.has_domain ? aws_acm_certificate_validation.frontend[0].certificate_arn : null
}

output "dns_alias" {
  description = "Route 53 ALIAS target for the custom domains; null without domain_name. CloudFront serves IPv6, so AAAA records are wanted too."
  value = local.has_domain ? {
    name    = aws_cloudfront_distribution.frontend.domain_name
    zone_id = aws_cloudfront_distribution.frontend.hosted_zone_id
    ipv6    = true
  } : null
}
