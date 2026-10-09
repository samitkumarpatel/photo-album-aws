variable "name" {
  description = "Short site name; also identifies its S3 bucket and CloudFront distribution."
  type        = string
}

variable "environment" {
  description = "Deployment environment used in the S3 bucket name."
  type        = string
}

variable "api_url" {
  description = "API Gateway base URL. CloudFront forwards /api and /actuator requests to this origin."
  type        = string
}

variable "domain_name" {
  description = "Optional custom domain for this CloudFront distribution."
  type        = string
  default     = null
}

variable "route53_zone_id" {
  description = "Route 53 hosted zone ID for ACM validation and the CloudFront alias record. Required with domain_name."
  type        = string
  default     = null

  validation {
    condition     = var.domain_name == null || var.route53_zone_id != null
    error_message = "route53_zone_id is required when domain_name is configured."
  }
}

variable "price_class" {
  description = "CloudFront price class."
  type        = string
  default     = "PriceClass_100"
}

variable "tags" {
  description = "Additional tags for supported resources."
  type        = map(string)
  default     = {}
}

variable "enable_versioning" {
  description = "Enable S3 object versioning for the frontend bucket."
  type        = bool
  default     = true
}

