variable "name" {
  description = "Prefix for the resources the stack creates on its own, such as the shared Lambda role."
  type        = string
}

variable "functions" {
  description = <<-EOT
    Container-image Lambda functions, keyed by function name. Each gets its own ECR repository (same name).
    On creation, `image` is copied into that repository as :bootstrap so the function can start. After that the
    pipeline pushes new images and updates the function; Terraform ignores the image from then on.
    Every function may use every table, bucket and queue in the stack.
  EOT
  type = map(object({
    image        = string                    # bootstrap image, e.g. "ghcr.io/owner/repo:latest"
    handler      = optional(string)          # overrides the image's CMD, e.g. "com.example.Handler::handleRequest"
    architecture = optional(string, "arm64") # must match the image
    memory       = optional(number, 1024)    # MB
    timeout      = optional(number, 30)      # seconds
    environment  = optional(map(string), {})
    public_url   = optional(bool, false) # create a public function URL (no auth)
    sqs_trigger  = optional(string)      # name of a queue in var.sqs that invokes the function
  }))
  default = {}

  validation {
    condition     = alltrue([for f in var.functions : f.sqs_trigger == null || contains([for q in var.sqs : q.name], f.sqs_trigger)])
    error_message = "sqs_trigger must name a queue listed in sqs."
  }
}

variable "s3_buckets" {
  description = "Names of private S3 buckets. Bucket names are global, so make them unique."
  type        = list(string)
  default     = []
}

variable "dynamodb" {
  description = "DynamoDB tables (on-demand), keyed by table name. Key attributes are strings."
  type = map(object({
    hash_key      = string
    range_key     = optional(string)
    indexes       = optional(map(object({ hash_key = string, range_key = optional(string) })), {}) # global secondary indexes
    ttl_attribute = optional(string)
  }))
  default = {}
}

variable "sqs" {
  description = "SQS queues. Each gets a <name>-dlq dead-letter queue. Set s3_bucket to have that bucket send ObjectCreated events to the queue."
  type = list(object({
    name      = string
    s3_bucket = optional(string)     # must be one of s3_buckets
    s3_prefix = optional(string, "") # only keys under this prefix
  }))
  default = []

  validation {
    condition     = alltrue([for q in var.sqs : q.s3_bucket == null || contains(var.s3_buckets, q.s3_bucket)])
    error_message = "s3_bucket must be one of s3_buckets."
  }
}
