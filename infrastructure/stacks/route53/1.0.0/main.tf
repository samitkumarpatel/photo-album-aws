# The DNS stack owns a public hosted zone and the user-facing ALIAS records that point at other stacks.
locals {
  zone_id = var.create_zone ? aws_route53_zone.this[0].zone_id : data.aws_route53_zone.this[0].zone_id

  # Record keys are static, so for_each stays plannable even while targets are still unknown.
  fqdns = { for name, _ in var.records : name => name == "@" ? var.dns : "${name}.${var.dns}" }
}

resource "aws_route53_zone" "this" {
  count = var.create_zone ? 1 : 0

  name    = var.dns
  comment = "Managed by Terraform"
  tags    = var.tags
}

data "aws_route53_zone" "this" {
  count = var.create_zone ? 0 : 1

  name         = var.dns
  private_zone = false
}

resource "aws_route53_record" "a" {
  for_each = var.records

  zone_id = local.zone_id
  name    = local.fqdns[each.key]
  type    = "A"

  alias {
    name                   = each.value.name
    zone_id                = each.value.zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "aaaa" {
  for_each = { for name, target in var.records : name => target if target.ipv6 }

  zone_id = local.zone_id
  name    = local.fqdns[each.key]
  type    = "AAAA"

  alias {
    name                   = each.value.name
    zone_id                = each.value.zone_id
    evaluate_target_health = false
  }
}
