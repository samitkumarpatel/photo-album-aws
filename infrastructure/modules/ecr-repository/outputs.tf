output "name" {
  description = "Repository name."
  value       = aws_ecr_repository.this.name
}

output "arn" {
  description = "Repository ARN."
  value       = aws_ecr_repository.this.arn
}

output "url" {
  description = "Repository URL, the image URI without a tag: <account>.dkr.ecr.<region>.amazonaws.com/<name>."
  value       = aws_ecr_repository.this.repository_url
}
