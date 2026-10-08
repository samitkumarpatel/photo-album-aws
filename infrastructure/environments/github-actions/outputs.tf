output "oidc_provider_arn" {
  description = "Account-level GitHub OIDC provider ARN."
  value       = module.github_actions.oidc_provider_arn
}

output "deploy_role_arn" {
  description = "Role ARN to configure as role-to-assume in GitHub Actions."
  value       = module.github_actions.deploy_role_arn
}
