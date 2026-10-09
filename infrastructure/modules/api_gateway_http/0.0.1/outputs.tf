output "api_id" {
  description = "HTTP API Gateway ID."
  value       = aws_apigatewayv2_api.this.id
}

output "api_endpoint" {
  description = "Invoke URL for the API Gateway default stage."
  value       = aws_apigatewayv2_stage.this.invoke_url
}

output "custom_domain_target" {
  description = "Regional target domain for DNS when a custom domain is configured."
  value       = local.has_domain ? aws_apigatewayv2_domain_name.this[0].domain_name_configuration[0].target_domain_name : null
}

output "custom_domain_hosted_zone_id" {
  description = "Regional hosted zone ID for an API custom domain."
  value       = local.has_domain ? aws_apigatewayv2_domain_name.this[0].domain_name_configuration[0].hosted_zone_id : null
}
