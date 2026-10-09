output "api_url" {
  description = "Base URL of the shared API Gateway HTTP API."
  value       = module.api_gateway_http.api_endpoint
}

output "api_custom_domain_target" {
  description = "Regional DNS target for the optional API custom domain."
  value       = module.api_gateway_http.custom_domain_target
}

output "api_custom_domain_hosted_zone_id" {
  description = "Regional hosted zone ID for the optional API custom domain."
  value       = module.api_gateway_http.custom_domain_hosted_zone_id
}

output "functions" {
  description = "Function names."
  value       = keys(aws_lambda_function.this)
}

output "ecr_repositories" {
  description = "ECR repository URL of each function, for the pipeline to push to."
  value       = { for name, r in aws_ecr_repository.this : name => r.repository_url }
}

output "s3_buckets" {
  description = "Bucket names."
  value       = keys(aws_s3_bucket.this)
}

output "dynamodb_tables" {
  description = "Table names."
  value       = keys(aws_dynamodb_table.this)
}

output "sqs_queue_urls" {
  description = "Queue URLs."
  value       = { for name, q in aws_sqs_queue.this : name => q.url }
}

output "sqs_dead_letter_urls" {
  description = "Dead-letter queue URLs: messages that failed 3 times; inspect and redrive from here."
  value       = { for name, q in aws_sqs_queue.dead_letter : name => q.url }
}
