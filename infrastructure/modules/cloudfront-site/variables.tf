variable "name" {
  description = "Name prefix for the distribution, origin access controls and functions."
  type        = string

  validation {
    condition     = length(var.name) <= 50
    error_message = "name must be at most 50 characters so derived resource names stay within CloudFront limits."
  }
}

variable "spa_bucket_regional_domain_name" {
  description = "Regional domain name of the bucket holding the built SPA."
  type        = string
}

variable "api_function_url" {
  description = "Lambda function URL that serves /api/*."
  type        = string
}

variable "api_origin_read_timeout_seconds" {
  description = "How long CloudFront waits for the API. Values above 60 need a quota increase."
  type        = number
  default     = 60
}

variable "media_bucket_regional_domain_name" {
  description = "Regional domain name of the media bucket, served at /media/*. Null leaves the media route out."
  type        = string
  default     = null
}

variable "media_trusted_key_group_ids" {
  description = "Key groups whose signed URLs the /media/* route accepts. Empty serves media without signatures."
  type        = list(string)
  default     = []
}

variable "aliases" {
  description = "Custom domain names. Used only together with acm_certificate_arn."
  type        = list(string)
  default     = []
}

variable "acm_certificate_arn" {
  description = "ACM certificate for the aliases. CloudFront requires it to be issued in us-east-1."
  type        = string
  default     = null

  validation {
    condition     = var.acm_certificate_arn == null || can(regex(":us-east-1:", var.acm_certificate_arn))
    error_message = "CloudFront only accepts ACM certificates from us-east-1."
  }
}

variable "price_class" {
  description = "Edge locations to use. PriceClass_100 covers North America and Europe at the lowest cost."
  type        = string
  default     = "PriceClass_100"

  validation {
    condition     = contains(["PriceClass_100", "PriceClass_200", "PriceClass_All"], var.price_class)
    error_message = "price_class must be PriceClass_100, PriceClass_200 or PriceClass_All."
  }
}
