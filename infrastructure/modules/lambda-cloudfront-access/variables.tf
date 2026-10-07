variable "function_name" {
  description = "Function name."
  type        = string
}

variable "qualifier" {
  description = "Alias the function URL belongs to."
  type        = string
}

variable "distribution_arn" {
  description = "ARN of the CloudFront distribution allowed to invoke."
  type        = string
}
