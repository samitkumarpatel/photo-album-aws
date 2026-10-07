variable "name" {
  description = "Queue name; the dead-letter queue and alarm use it as a prefix."
  type        = string
}

variable "visibility_timeout_seconds" {
  description = "How long a received message stays hidden. AWS recommends at least six times the consuming function's timeout."
  type        = number
  default     = 720
}

variable "message_retention_seconds" {
  description = "How long unprocessed messages are kept."
  type        = number
  default     = 345600 # 4 days
}

variable "max_receive_count" {
  description = "Attempts before a message moves to the dead-letter queue."
  type        = number
  default     = 3
}

variable "s3_source_bucket_arn" {
  description = "Bucket allowed to send event notifications to the queue. Null allows none."
  type        = string
  default     = null
}

variable "s3_source_account_id" {
  description = "Account that owns s3_source_bucket_arn; guards against a bucket of the same name in another account."
  type        = string
  default     = null
}

variable "alarm_actions" {
  description = "ARNs notified when dead letters appear and when they are cleared, for example an SNS topic."
  type        = list(string)
  default     = []
}
