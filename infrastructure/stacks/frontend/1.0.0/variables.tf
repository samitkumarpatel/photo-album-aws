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

variable "media_bucket_name" {
  description = "Optional private S3 media bucket to serve through this distribution."
  type        = string
  default     = null
}

variable "media_path_prefix" {
  description = "Object key prefix routed to the private media bucket."
  type        = string
  default     = "photo-album"
}

variable "media_key_group_id" {
  description = "CloudFront key group required to access media objects when media_bucket_name is configured."
  type        = string
  default     = null

  validation {
    condition     = var.media_bucket_name == null || var.media_key_group_id != null
    error_message = "media_key_group_id is required when media_bucket_name is configured."
  }
}

variable "domain_name" {
  description = "Optional custom domain for this CloudFront distribution."
  type        = string
  default     = null
}

variable "alternative_domain_names" {
  description = "Extra custom domains served by this distribution, such as \"www.example.com\". Added to the certificate and CloudFront aliases."
  type        = list(string)
  default     = []

  validation {
    condition     = var.domain_name != null || length(var.alternative_domain_names) == 0
    error_message = "alternative_domain_names requires domain_name."
  }
}

variable "route53_zone_id" {
  description = "Route 53 hosted zone ID for ACM validation and the CloudFront alias records. Required with domain_name."
  type        = string
  default     = null

  validation {
    condition     = var.domain_name == null || var.route53_zone_id != null
    error_message = "route53_zone_id is required when domain_name is configured."
  }
}

variable "create_alias_records" {
  description = "Create A/AAAA alias records for the custom domains. Set false when another stack, such as stacks/route53, owns them via the dns_alias output."
  type        = bool
  default     = true
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

