output "distribution_id" {
  description = "Distribution id, used for cache invalidations."
  value       = aws_cloudfront_distribution.this.id
}

output "distribution_arn" {
  description = "Distribution ARN, used in bucket and Lambda resource policies."
  value       = aws_cloudfront_distribution.this.arn
}

output "domain_name" {
  description = "CloudFront domain name."
  value       = aws_cloudfront_distribution.this.domain_name
}

output "url" {
  description = "Public site URL: the first custom domain if configured, otherwise the CloudFront domain."
  value       = "https://${local.custom_domain ? var.aliases[0] : aws_cloudfront_distribution.this.domain_name}"
}
