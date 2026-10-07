output "function_name" {
  description = "Function name."
  value       = aws_lambda_function.this.function_name
}

output "function_arn" {
  description = "Unqualified function ARN."
  value       = aws_lambda_function.this.arn
}

output "alias_name" {
  description = "Alias serving the function URL."
  value       = aws_lambda_alias.live.name
}

output "published_version" {
  description = "Version the alias points at."
  value       = aws_lambda_function.this.version
}

output "function_url" {
  description = "Function URL (IAM auth; reachable only through CloudFront)."
  value       = aws_lambda_function_url.this.function_url
}

output "image_uri" {
  description = "Image the published version runs."
  value       = aws_lambda_function.this.image_uri
}

output "role_arn" {
  description = "Execution role ARN."
  value       = aws_iam_role.this.arn
}

output "log_group_name" {
  description = "CloudWatch log group."
  value       = aws_cloudwatch_log_group.this.name
}
