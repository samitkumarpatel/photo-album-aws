variable "name" {
  description = "IAM deploy role name, usually derived from the repository slug."
  type        = string
}

variable "repos" {
  description = "GitHub repositories allowed to assume this role, as owner/repository slugs."
  type        = set(string)

  validation {
    condition = length(var.repos) > 0 && alltrue([
      for repo in var.repos : length(split("/", repo)) == 2 &&
      alltrue([for part in split("/", repo) : part != ""])
    ])
    error_message = "At least one repository is required, each in owner/repository format."
  }
}

variable "environments" {
  description = "GitHub Environments allowed to assume this role."
  type        = set(string)
  default     = ["dev", "prod"]

  validation {
    condition     = length(var.environments) > 0
    error_message = "At least one GitHub Environment must be allowed to assume the role."
  }
}

variable "create_oidc_provider" {
  description = "Create and own the account-level GitHub OIDC provider from this module instance. Set true on exactly one instance."
  type        = bool
  default     = false
}
