output "site_url" {
  description = "Public URL of the application."
  value       = module.site.url
}

output "cloudfront_distribution_id" {
  description = "Distribution id, for cache invalidations after deploying the SPA."
  value       = module.site.distribution_id
}

output "cloudfront_domain_name" {
  description = "CloudFront domain name; point DNS for custom domains here."
  value       = module.site.domain_name
}

output "spa_bucket" {
  description = "Bucket for the built frontend."
  value       = module.spa_bucket.id
}

output "media_bucket" {
  description = "Bucket for photos and videos."
  value       = module.media_bucket.id
}

output "data_table" {
  description = "DynamoDB table for albums, photos and share links."
  value       = module.data_table.name
}

output "api_repository_url" {
  description = "ECR repository to push the API image to."
  value       = module.api_repository.url
}

output "api_image_uri" {
  description = "Image the API currently runs."
  value       = module.api.image_uri
}

output "api_function_name" {
  description = "API Lambda function name."
  value       = module.api.function_name
}

output "api_alias" {
  description = "Alias serving traffic."
  value       = module.api.alias_name
}

output "api_function_url" {
  description = "Function URL. It only accepts requests signed by CloudFront; use site_url instead."
  value       = module.api.function_url
}

output "api_log_group" {
  description = "API log group."
  value       = module.api.log_group_name
}

output "processing_queue_url" {
  description = "Queue of uploaded originals waiting for processing."
  value       = module.processing_queue.url
}

output "processing_dead_letter_url" {
  description = "Uploads that failed processing repeatedly; inspect and redrive from here."
  value       = module.processing_queue.dead_letter_url
}

output "worker_function_name" {
  description = "Upload processing worker function."
  value       = module.worker.function_name
}
