variable "name" {
  description = "Budget name."
  type        = string
}

variable "monthly_limit_usd" {
  description = "Monthly cost limit in US dollars."
  type        = number

  validation {
    condition     = var.monthly_limit_usd > 0
    error_message = "monthly_limit_usd must be positive."
  }
}

variable "alert_emails" {
  description = "Email addresses that receive budget alerts."
  type        = list(string)

  validation {
    condition     = length(var.alert_emails) > 0 && alltrue([for e in var.alert_emails : can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", e))])
    error_message = "Provide at least one valid email address."
  }
}

variable "actual_thresholds_percent" {
  description = "Alert when actual spend passes these percentages of the limit."
  type        = list(number)
  default     = [80, 100]
}
