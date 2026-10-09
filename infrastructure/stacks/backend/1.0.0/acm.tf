# Optional regional certificate for the API custom domain, validated through Route 53.
# Created when api_domain_name is set without api_certificate_arn; api_route53_zone_id is then required.
# The decision uses only plan-time inputs: the zone ID usually comes from a zone created in the same apply.
locals {
  create_api_certificate = var.api_domain_name != null && var.api_certificate_arn == null
}

resource "aws_acm_certificate" "api" {
  count = local.create_api_certificate ? 1 : 0

  domain_name       = var.api_domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "api_certificate_validation" {
  for_each = local.create_api_certificate ? {
    for option in aws_acm_certificate.api[0].domain_validation_options : option.domain_name => {
      name   = option.resource_record_name
      record = option.resource_record_value
      type   = option.resource_record_type
    }
  } : {}

  zone_id         = var.api_route53_zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "api" {
  count = local.create_api_certificate ? 1 : 0

  certificate_arn         = aws_acm_certificate.api[0].arn
  validation_record_fqdns = [for record in aws_route53_record.api_certificate_validation : record.fqdn]
}
