variable "name" {
  description = "Name of the GitHub Actions deployment role."
  type        = string
}

variable "repository_owner" {
  description = "GitHub account that owns the repository."
  type        = string
}

variable "repository_owner_id" {
  description = "Immutable numeric GitHub owner ID used in this repository's OIDC subject."
  type        = string
}

variable "repository_name" {
  description = "GitHub repository name without the owner or .git suffix."
  type        = string
}

variable "repository_id" {
  description = "Immutable numeric GitHub repository ID used in this repository's OIDC subject."
  type        = string
}

variable "environment_names" {
  description = "GitHub Environments allowed to assume the deploy role."
  type        = set(string)

  validation {
    condition     = length(var.environment_names) > 0
    error_message = "At least one GitHub Environment must be allowed to deploy."
  }
}

variable "account_id" {
  description = "AWS account ID that owns the GitHub OIDC provider and deploy role."
  type        = string
}

variable "region" {
  description = "AWS region containing the ECR repositories and Lambda functions."
  type        = string
}
