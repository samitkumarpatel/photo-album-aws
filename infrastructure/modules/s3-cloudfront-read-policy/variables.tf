variable "bucket_id" {
  description = "Bucket name."
  type        = string
}

variable "bucket_arn" {
  description = "Bucket ARN."
  type        = string
}

variable "distribution_arn" {
  description = "ARN of the CloudFront distribution allowed to read."
  type        = string
}

variable "object_prefix" {
  description = "Key prefix CloudFront may read. Empty means every object."
  type        = string
  default     = ""
}
