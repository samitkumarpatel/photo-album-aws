output "site_url" {
  description = "Public URL of the application."
  value       = module.photo_album.site_url
}

output "cloudfront_distribution_id" {
  description = "Distribution id, for cache invalidations."
  value       = module.photo_album.cloudfront_distribution_id
}

output "cloudfront_domain_name" {
  description = "CloudFront domain name."
  value       = module.photo_album.cloudfront_domain_name
}

output "spa_bucket" {
  description = "Bucket for the built frontend."
  value       = module.photo_album.spa_bucket
}

output "media_bucket" {
  description = "Bucket for photos and videos."
  value       = module.photo_album.media_bucket
}

output "data_table" {
  description = "DynamoDB table name."
  value       = module.photo_album.data_table
}

output "api_repository_url" {
  description = "ECR repository to push the API image to."
  value       = module.photo_album.api_repository_url
}

output "api_image_uri" {
  description = "Image the API currently runs."
  value       = module.photo_album.api_image_uri
}

output "api_function_name" {
  description = "API Lambda function name."
  value       = module.photo_album.api_function_name
}

output "api_log_group" {
  description = "API log group."
  value       = module.photo_album.api_log_group
}

output "processing_queue_url" {
  description = "Queue of uploaded originals waiting for processing."
  value       = module.photo_album.processing_queue_url
}

output "processing_dead_letter_url" {
  description = "Uploads that failed processing repeatedly."
  value       = module.photo_album.processing_dead_letter_url
}

output "worker_function_name" {
  description = "Upload processing worker function."
  value       = module.photo_album.worker_function_name
}
