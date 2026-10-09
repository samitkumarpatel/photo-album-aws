variable "name" {
  description = "Prefix for the resources the stack creates on its own, such as the shared Lambda role."
  type        = string
}

variable "api_cors_allow_origins" {
  description = "Browser origins allowed by API Gateway CORS. Empty disables API level CORS."
  type        = list(string)
  default     = []
}

variable "api_enable_access_logs" {
  description = "Enable API Gateway access logs in CloudWatch."
  type        = bool
  default     = false
}

variable "api_log_retention_days" {
  description = "API Gateway access log retention when logging is enabled."
  type        = number
  default     = 7
}

variable "api_domain_name" {
  description = "Optional custom API Gateway domain name."
  type        = string
  default     = null
}

variable "api_certificate_arn" {
  description = "ACM certificate ARN for api_domain_name."
  type        = string
  default     = null
}

variable "api_route_throttling" {
  description = "Optional API Gateway route throttling limits, keyed by route key."
  type = map(object({
    burst_limit = number
    rate_limit  = number
  }))
  default = {}
}

variable "lambda" {
  description = <<-EOT
    Container-image Lambda functions, keyed by function name. Each gets its own ECR repository (same name).
    On creation, `source_image` is copied into that repository when it is empty. `source_image_tag` is used
    for the seeded ECR image. After that the pipeline pushes new images and updates the function; Terraform
    ignores image URI changes from then on.
    Every function may use every table, bucket and queue in the stack.
  EOT
  type = map(object({
    source_image     = optional(string)           # optional source image override for the initial ECR seed
    source_image_tag = optional(string)           # optional destination tag override for the seed image
    handler          = optional(string)           # overrides the image's CMD, e.g. "com.example.Handler::handleRequest"
    architecture     = optional(string, "x86_64") # must match the image
    memory           = optional(number, 1024)     # MB
    timeout          = optional(number, 30)       # seconds
    environment      = optional(map(string), {})
    force_delete     = optional(bool, true)      # ECR repository deletion behavior; set false for prod
    http_router      = optional(map(string), {}) # public path => path handled by the Lambda
    sqs_trigger      = optional(string)          # name of a queue in var.sqs that invokes the function
  }))
  default = {}

  validation {
    condition     = alltrue([for f in var.lambda : f.sqs_trigger == null || contains([for q in var.sqs : q.name], f.sqs_trigger)])
    error_message = "sqs_trigger must name a queue listed in sqs."
  }

  validation {
    condition = alltrue(flatten([
      for f in var.lambda : [
        for public_path, lambda_path in f.http_router :
        startswith(public_path, "/") && startswith(lambda_path, "/") &&
        !strcontains(public_path, " ") && !strcontains(lambda_path, " ")
      ]
    ]))
    error_message = "http_router paths must start with / and must not contain spaces."
  }

  validation {
    condition = length(flatten([
      for f in var.lambda : [for public_path, _ in f.http_router : public_path]
      ])) == length(distinct(flatten([
        for f in var.lambda : [for public_path, _ in f.http_router : public_path]
    ])))
    error_message = "Each public http_router path may be assigned to only one Lambda."
  }

  validation {
    condition = alltrue([
      for route_key in keys(var.api_route_throttling) :
      contains(flatten([for f in var.lambda : flatten([
        for public_path, _ in f.http_router : [
          "ANY ${public_path}",
          "ANY ${public_path}/{proxy+}",
        ]
      ])]), route_key)
    ])
    error_message = "api_route_throttling keys must match a route generated from lambda.http_router."
  }
}

variable "s3" {
  description = "Names of private S3 buckets. Bucket names are global, so make them unique."
  type        = list(string)
  default     = []
}

variable "dynamodb" {
  description = "DynamoDB tables (on-demand), keyed by table name. Key attributes are strings."
  type = map(object({
    hash_key            = string
    range_key           = optional(string)
    indexes             = optional(map(object({ hash_key = string, range_key = optional(string) })), {}) # global secondary indexes
    ttl_attribute       = optional(string)
    deletion_protection = optional(bool, false)
  }))
  default = {}
}

variable "sqs" {
  description = "SQS queues. Each gets a <name>-dlq dead-letter queue. Set s3_bucket to have that bucket send ObjectCreated events to the queue."
  type = list(object({
    name      = string
    s3_bucket = optional(string)     # must be one of s3 bucket names
    s3_prefix = optional(string, "") # only keys under this prefix
  }))
  default = []

  validation {
    condition     = alltrue([for q in var.sqs : q.s3_bucket == null || contains(var.s3, q.s3_bucket)])
    error_message = "s3_bucket must be one of s3."
  }
}
