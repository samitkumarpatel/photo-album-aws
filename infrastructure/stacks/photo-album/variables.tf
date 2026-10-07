variable "project" {
  description = "Project name, used as the first part of every resource name."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}$", var.project))
    error_message = "project must be 2-21 lowercase letters, digits or hyphens, starting with a letter."
  }
}

variable "environment" {
  description = "Environment name such as dev or prod."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{1,9}$", var.environment))
    error_message = "environment must be 2-10 lowercase letters or digits, starting with a letter."
  }
}

# ---- API ----

variable "api_image_tag" {
  description = "Tag of the API image in the stack's ECR repository. Use a new tag per release (for example the git SHA); Lambda only redeploys when it changes."
  type        = string

  validation {
    condition     = can(regex("^[\\w][\\w.-]{0,127}$", var.api_image_tag))
    error_message = "api_image_tag must be a valid Docker tag."
  }
}

variable "api_image_command" {
  description = "Optional override of the image CMD (the Lambda handler). Null uses the image's own CMD."
  type        = list(string)
  default     = null
}

variable "api_image_immutable_tags" {
  description = "Make ECR tags immutable so a tag always means the same image."
  type        = bool
  default     = true
}

variable "api_image_keep_count" {
  description = "How many API images ECR keeps for rollbacks."
  type        = number
  default     = 10
}

variable "lambda_architecture" {
  description = "arm64 or x86_64; must match the platform the image was built for."
  type        = string
  default     = "arm64"
}

variable "lambda_memory_mb" {
  description = "API memory in MB."
  type        = number
  default     = 2048
}

variable "lambda_timeout_seconds" {
  description = "API timeout in seconds."
  type        = number
  default     = 30
}

variable "lambda_extra_environment" {
  description = "Additional environment variables for the API. The stack sets the data and storage variables itself."
  type        = map(string)
  default     = {}
}

variable "worker_memory_mb" {
  description = "Memory for the upload processing worker in MB."
  type        = number
  default     = 2048
}

variable "worker_timeout_seconds" {
  description = "Timeout for the upload processing worker in seconds; the queue's visibility timeout is six times this."
  type        = number
  default     = 120
}

variable "worker_maximum_concurrency" {
  description = "Most worker invocations the processing queue runs at once."
  type        = number
  default     = 5
}

variable "log_retention_days" {
  description = "API log retention in days."
  type        = number
  default     = 30
}

# ---- Data ----

variable "table_point_in_time_recovery" {
  description = "Continuous backups for the data table."
  type        = bool
  default     = true
}

variable "table_deletion_protection" {
  description = "Protect the data table from deletion."
  type        = bool
  default     = true
}

# ---- Media ----

variable "media_prefix" {
  description = "Key prefix the app writes media under (PHOTO_ALBUM_S3_PREFIX)."
  type        = string
  default     = "photo-album"
}

variable "media_versioning" {
  description = "Keep previous versions of media objects."
  type        = bool
  default     = false
}

variable "force_destroy_buckets" {
  description = "Let terraform destroy delete the media bucket and the ECR repository with their contents. Only for throw-away environments."
  type        = bool
  default     = false
}

variable "enable_media_route" {
  description = "Serve the media bucket at /media/* through CloudFront, accepting only URLs signed with a generated key pair. The API signs URLs for this route once media_cdn_domain (or a custom domain) is known."
  type        = bool
  default     = false
}

variable "media_cdn_domain" {
  description = "Public domain in signed media URLs. Null uses the first custom domain; without one, set it to the cloudfront_domain_name output after the first apply."
  type        = string
  default     = null
}

# ---- Delivery ----

variable "domain_aliases" {
  description = "Custom domain names for the site. Requires acm_certificate_arn."
  type        = list(string)
  default     = []
}

variable "acm_certificate_arn" {
  description = "ACM certificate in us-east-1 covering domain_aliases."
  type        = string
  default     = null
}

variable "price_class" {
  description = "CloudFront price class."
  type        = string
  default     = "PriceClass_100"
}

# ---- Cost ----

variable "budget_monthly_limit_usd" {
  description = "Monthly cost budget in US dollars."
  type        = number
}

variable "budget_alert_emails" {
  description = "Recipients of budget alerts."
  type        = list(string)
}
