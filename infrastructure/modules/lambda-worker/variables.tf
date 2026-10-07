variable "name" {
  description = "Function name; also prefixes the IAM role and log group."
  type        = string
}

variable "description" {
  description = "Function description."
  type        = string
  default     = "Queue worker"
}

variable "image_uri" {
  description = "Container image in ECR, as <repository-url>:<tag> or <repository-url>@sha256:<digest>."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}\\.dkr\\.ecr\\.[a-z0-9-]+\\.amazonaws\\.com(\\.cn)?/.+(:[\\w][\\w.-]{0,127}|@sha256:[a-f0-9]{64})$", var.image_uri))
    error_message = "image_uri must be a private ECR image with a tag or digest."
  }
}

variable "image_command" {
  description = "Handler that overrides the image CMD, for example [\"com.example.WorkerHandler::handleRequest\"]."
  type        = list(string)
}

variable "architecture" {
  description = "Instruction set; must match the image platform."
  type        = string
  default     = "arm64"

  validation {
    condition     = contains(["arm64", "x86_64"], var.architecture)
    error_message = "architecture must be arm64 or x86_64."
  }
}

variable "memory_mb" {
  description = "Memory in MB. Decoding large images needs heap, and CPU scales with memory."
  type        = number
  default     = 2048

  validation {
    condition     = var.memory_mb >= 128 && var.memory_mb <= 10240
    error_message = "memory_mb must be between 128 and 10240."
  }
}

variable "timeout_seconds" {
  description = "Function timeout in seconds. Keep the queue's visibility timeout at least six times this."
  type        = number
  default     = 120

  validation {
    condition     = var.timeout_seconds >= 1 && var.timeout_seconds <= 900
    error_message = "timeout_seconds must be between 1 and 900."
  }
}

variable "ephemeral_storage_mb" {
  description = "Size of /tmp in MB; originals are copied there while processing."
  type        = number
  default     = 2048

  validation {
    condition     = var.ephemeral_storage_mb >= 512 && var.ephemeral_storage_mb <= 10240
    error_message = "ephemeral_storage_mb must be between 512 and 10240."
  }
}

variable "alias_name" {
  description = "Alias that points at the latest published version and receives the queue's messages."
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

variable "queue_arn" {
  description = "SQS queue the function consumes."
  type        = string
}

variable "batch_size" {
  description = "Messages per invocation. Small batches keep one slow image from delaying many others."
  type        = number
  default     = 5
}

variable "batching_window_seconds" {
  description = "How long SQS may wait to fill a batch."
  type        = number
  default     = 0
}

variable "maximum_concurrency" {
  description = "Upper bound on concurrent invocations from the queue (2 to 1000)."
  type        = number
  default     = 5
}

variable "dynamodb_table_arn" {
  description = "ARN of the album data table."
  type        = string
}

variable "dynamodb_index_names" {
  description = "Secondary indexes the function queries."
  type        = list(string)
  default     = []
}

variable "media_bucket_arn" {
  description = "ARN of the media bucket."
  type        = string
}

variable "media_read_prefixes" {
  description = "Key prefixes the function may read, each with a trailing slash."
  type        = list(string)
  default     = []
}

variable "media_write_prefixes" {
  description = "Key prefixes the function may write and delete, each with a trailing slash."
  type        = list(string)
  default     = []
}
