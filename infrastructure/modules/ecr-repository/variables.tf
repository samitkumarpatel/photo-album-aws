variable "name" {
  description = "Repository name."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*$", var.name))
    error_message = "Repository names use lowercase letters, digits, and . _ - / separators."
  }
}

variable "immutable_tags" {
  description = "Refuse to overwrite an existing tag. Recommended: Terraform only redeploys Lambda when the image URI changes, so every release needs a new tag."
  type        = bool
  default     = true
}

variable "keep_images" {
  description = "How many images to keep for rollbacks."
  type        = number
  default     = 10
}

variable "force_delete" {
  description = "Let terraform destroy delete the repository with its images. Only for throw-away environments."
  type        = bool
  default     = false
}
