variable "name" {
  description = "Globally unique bucket name."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.name))
    error_message = "Bucket names must be 3-63 lowercase letters, digits, dots or hyphens."
  }
}

variable "force_destroy" {
  description = "Allow terraform destroy to delete a bucket that still contains objects. Use only for throw-away environments."
  type        = bool
  default     = false
}

variable "versioning" {
  description = "Keep previous object versions."
  type        = bool
  default     = false
}

variable "lifecycle_rules" {
  description = "Lifecycle rules. Each rule applies to one key prefix; an empty prefix means the whole bucket."
  type = list(object({
    id                              = string
    prefix                          = string
    transition_days                 = optional(number)
    transition_storage_class        = optional(string, "INTELLIGENT_TIERING")
    expiration_days                 = optional(number)
    abort_incomplete_multipart_days = optional(number)
  }))
  default = []
}

variable "cors_rules" {
  description = "CORS rules, for example to let the browser upload with presigned PUT URLs."
  type = list(object({
    allowed_methods = list(string)
    allowed_origins = list(string)
    allowed_headers = optional(list(string), ["*"])
    expose_headers  = optional(list(string), ["ETag"])
    max_age_seconds = optional(number, 3000)
  }))
  default = []
}
