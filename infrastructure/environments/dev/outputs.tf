output "api_url" {
  description = "Public URL of the API function."
  value       = module.photo_album.function_urls["photo-album-dev-api"]
}

output "media_bucket" {
  description = "Bucket for photos and videos."
  value       = local.bucket
}

output "sqs_dead_letter_urls" {
  description = "Messages that failed processing 3 times."
  value       = module.photo_album.sqs_dead_letter_urls
}

output "ecr_repositories" {
  description = "Where the pipeline pushes each function's image."
  value       = module.photo_album.ecr_repositories
}
