variable "github_repository" {
  description = "GitHub repository that owns the deployment workflow, in owner/repository format."
  type        = string
}

variable "github_environments" {
  description = "GitHub Environments that may deploy the application. Keep aligned with dev/prod workflow environments."
  type        = set(string)
}
