variable "name" {
  description = "Table name. The application reads it from PHOTO_ALBUM_TABLE."
  type        = string
}

variable "point_in_time_recovery" {
  description = "Keep continuous backups for the last 35 days."
  type        = bool
  default     = true
}

variable "deletion_protection" {
  description = "Block table deletion until this is turned off. Recommended outside dev."
  type        = bool
  default     = true
}

variable "kms_key_arn" {
  description = "Customer managed KMS key for encryption at rest. Null keeps the AWS owned key, which is always on."
  type        = string
  default     = null
}
