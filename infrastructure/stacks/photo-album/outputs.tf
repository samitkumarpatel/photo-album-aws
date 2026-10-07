output "function_urls" {
  description = "Public URL of each function with public_url = true."
  value       = { for name, u in aws_lambda_function_url.this : name => u.function_url }
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
