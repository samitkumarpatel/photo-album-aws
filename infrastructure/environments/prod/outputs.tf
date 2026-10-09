output "api_url" {
  description = "Base URL of the API Gateway HTTP API."
  value       = module.photo_album.api_url
}

output "media_bucket" {
  description = "Bucket for photos and videos."
  value       = one(module.photo_album.s3_buckets)
}

output "sqs_dead_letter_urls" {
  description = "Messages that failed image processing three times."
  value       = module.photo_album.sqs_dead_letter_urls
}

output "ecr_repositories" {
  description = "Where the release pipeline pushes each function image."
  value       = module.photo_album.ecr_repositories
}
