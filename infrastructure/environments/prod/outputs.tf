output "api_url" {
  description = "Public URL of the API function."
  value       = module.photo_album.function_urls["photo-album-prod-api"]
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
