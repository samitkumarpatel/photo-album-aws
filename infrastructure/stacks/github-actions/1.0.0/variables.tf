variable "name" {
  description = "Name of the GitHub Actions deployment role."
  type        = string
}

variable "repository" {
  description = "GitHub repository allowed to deploy, in owner/repository format."
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
