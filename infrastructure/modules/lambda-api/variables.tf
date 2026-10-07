variable "name" {
  description = "Function name; also prefixes the IAM role and log group."
  type        = string
}

variable "description" {
  description = "Function description."
  type        = string
  default     = "Photo album REST API"
}

variable "image_uri" {
  description = "Container image in ECR, as <repository-url>:<tag> or <repository-url>@sha256:<digest>. It must be pushed before apply."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}\\.dkr\\.ecr\\.[a-z0-9-]+\\.amazonaws\\.com(\\.cn)?/.+(:[\\w][\\w.-]{0,127}|@sha256:[a-f0-9]{64})$", var.image_uri))
    error_message = "image_uri must be a private ECR image with a tag or digest."
  }
}

variable "architecture" {
  description = "Instruction set; must match the GraalVM native image platform."
  type        = string
  default     = "arm64"

  validation {
    condition     = contains(["arm64", "x86_64"], var.architecture)
    error_message = "architecture must be arm64 or x86_64."
  }
}

variable "memory_mb" {
  description = "Memory in MB. CPU scales with memory; Spring starts noticeably faster at 2048 or more."
  type        = number
  default     = 2048

  validation {
    condition     = var.memory_mb >= 128 && var.memory_mb <= 10240
    error_message = "memory_mb must be between 128 and 10240."
  }
}

variable "timeout_seconds" {
  description = "Function timeout in seconds. CloudFront waits at most 60 seconds for an origin by default."
  type        = number
  default     = 30

  validation {
    condition     = var.timeout_seconds >= 1 && var.timeout_seconds <= 900
    error_message = "timeout_seconds must be between 1 and 900."
  }
}

variable "alias_name" {
  description = "Alias that points at the latest published version and serves the function URL."
  type        = string
  default     = "live"
}

variable "environment" {
  description = "Environment variables for the application. Do not set AWS_REGION; Lambda reserves it."
  type        = map(string)
  default     = {}

  validation {
    condition     = !contains(keys(var.environment), "AWS_REGION")
    error_message = "AWS_REGION is reserved by Lambda and set automatically."
  }
}

variable "log_retention_days" {
  description = "CloudWatch log retention in days."
  type        = number
  default     = 30
}

variable "dynamodb_table_arn" {
  description = "ARN of the album data table."
  type        = string
}

variable "dynamodb_index_names" {
  description = "Secondary indexes the function queries."
  type        = list(string)
  default     = ["gsi1"]
}

variable "media_bucket_arn" {
  description = "ARN of the media bucket."
  type        = string
}

variable "media_object_prefix" {
  description = "Key prefix the function may read, write and delete, including the trailing slash. Empty grants the whole bucket."
  type        = string
  default     = ""
}
