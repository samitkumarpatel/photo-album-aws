variable "dns" {
  description = "Hosted zone domain name, for example \"example.com\". Must be a domain you own."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$", var.dns))
    error_message = "dns must be a lowercase domain name without a trailing dot, for example \"example.com\"."
  }
}

variable "records" {
  description = <<-EOT
    Route 53 ALIAS records, keyed by record name relative to dns. Use "@" for the zone apex.
    Each value is a stack's `dns_alias` output: the AWS target hostname, its hosted zone ID, and whether
    the target also serves IPv6 (adds an AAAA record next to the A record).
  EOT
  type = map(object({
    name    = string
    zone_id = string
    ipv6    = optional(bool, false)
  }))
  default = {}

  validation {
    condition     = alltrue([for target in values(var.records) : target != null])
    error_message = "Every record target must be set; a null dns_alias means that stack has no custom domain configured."
  }

  validation {
    condition     = alltrue([for name in keys(var.records) : name == "@" || can(regex("^[a-z0-9*]([a-z0-9.-]*[a-z0-9])?$", name))])
    error_message = "Record keys must be \"@\" for the apex or a lowercase name relative to dns, such as \"www\" or \"api\"."
  }
}

variable "create_zone" {
  description = "Create the public hosted zone. Set false to add records to an existing public zone with the same name."
  type        = bool
  default     = true
}

variable "tags" {
  description = "Additional tags for the hosted zone."
  type        = map(string)
  default     = {}
}
