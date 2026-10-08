output "oidc_provider_arn" {
  description = "Account-level GitHub OIDC provider ARN."
  value       = aws_iam_openid_connect_provider.github.arn
}

output "deploy_role_arn" {
  description = "Role ARN configured in the GitHub Actions workflow."
  value       = aws_iam_role.github_deploy.arn
}
