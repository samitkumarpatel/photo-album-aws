output "name" {
  description = "Table name."
  value       = aws_dynamodb_table.this.name
}

output "arn" {
  description = "Table ARN. Index ARNs are this value followed by /index/<name>."
  value       = aws_dynamodb_table.this.arn
}
