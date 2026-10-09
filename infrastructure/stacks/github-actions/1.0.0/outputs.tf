output "role_arn" {
  description = "Deploy role ARN for this GitHub repository module instance."
  value       = aws_iam_role.this.arn
}

output "role_name" {
  description = "Deploy role name for this GitHub repository module instance."
  value       = aws_iam_role.this.name
}

output "oidc_provider_arn" {
  description = "Account-level GitHub OIDC provider ARN used by this deploy role."
  value       = local.oidc_provider_arn
}
