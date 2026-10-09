output "api_url" {
  description = "Base URL of the API Gateway HTTP API."
  value       = module.photo_album.api_url
}

output "dns" {
  description = "Hosted zone details. Set name_servers as the domain's NS records at the registrar."
  value = {
    zone_id      = module.route53.zone_id
    name_servers = module.route53.name_servers
    records      = module.route53.fqdns
  }
}

output "frontend_sites" {
  description = "Frontend bucket, CloudFront, and URL details keyed by site name."
  value = {
    for name, site in module.frontend : name => {
      bucket_name       = site.bucket_name
      distribution_id   = site.distribution_id
      distribution_host = site.distribution_domain_name
      site_url          = site.site_url
      certificate_arn   = site.certificate_arn
    }
  }
}

output "media_bucket" {
  description = "Bucket for photos and videos."
  value       = one(module.photo_album.s3_buckets)
}

output "sqs_dead_letter_urls" {
  description = "Messages that failed processing 3 times."
  value       = module.photo_album.sqs_dead_letter_urls
}

output "ecr_repositories" {
  description = "Where the pipeline pushes each function's image."
  value       = module.photo_album.ecr_repositories
}
