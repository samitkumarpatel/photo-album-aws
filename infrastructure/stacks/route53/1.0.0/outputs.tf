output "zone_id" {
  description = "Hosted zone ID, for ACM DNS validation records in other stacks."
  value       = local.zone_id
}

output "name_servers" {
  description = "Name servers to set at the domain registrar. Delegation is required before ACM validation can finish."
  value       = var.create_zone ? aws_route53_zone.this[0].name_servers : data.aws_route53_zone.this[0].name_servers
}

output "fqdns" {
  description = "Fully qualified record names, keyed by record key."
  value       = { for name, record in aws_route53_record.a : name => record.fqdn }
}
