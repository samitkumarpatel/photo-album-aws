variable "name" {
  description = "Name for the HTTP API and its access log group."
  type        = string
}

variable "integrations" {
  description = "HTTP API route keys mapped to Lambda integrations."
  type = map(object({
    lambda_invoke_arn    = string
    lambda_function_name = string
    path_rewrite         = optional(string)
    proxy_path_rewrite   = optional(string)
  }))
  default = {}
}

variable "cors_allow_origins" {
  description = "Allowed browser origins. Empty disables API level CORS handling."
  type        = list(string)
  default     = []
}

variable "cors_allow_methods" {
  description = "HTTP methods allowed in CORS preflight responses."
  type        = list(string)
  default     = ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"]
}

variable "cors_allow_headers" {
  description = "Request headers allowed in CORS preflight responses."
  type        = list(string)
  default     = ["Content-Type", "Authorization", "X-Amz-Date", "X-Api-Key", "X-Amz-Security-Token"]
}

variable "cors_max_age" {
  description = "Browser cache duration for CORS preflight responses, in seconds."
  type        = number
  default     = 300
}

variable "enable_access_logs" {
  description = "Whether to create a CloudWatch log group and enable API access logs."
  type        = bool
  default     = false
}

variable "log_retention_days" {
  description = "Retention for API access logs when logging is enabled."
  type        = number
  default     = 7
}

variable "domain_name" {
  description = "Optional custom domain name for the API."
  type        = string
  default     = null
}

variable "certificate_arn" {
  description = "ACM certificate ARN used with domain_name."
  type        = string
  default     = null
}

variable "route_throttling" {
  description = "Optional per route throttling settings, keyed by API Gateway route key."
  type = map(object({
    burst_limit = number
    rate_limit  = number
  }))
  default = {}
}

variable "tags" {
  description = "Tags applied to API Gateway resources that support tags."
  type        = map(string)
  default     = {}
}
