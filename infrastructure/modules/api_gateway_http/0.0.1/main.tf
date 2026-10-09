locals {
  lambda_names = toset([for integration in values(var.integrations) : integration.lambda_function_name])
  has_domain   = var.domain_name != null
  has_cors     = length(var.cors_allow_origins) > 0
}

resource "aws_cloudwatch_log_group" "access_logs" {
  count             = var.enable_access_logs ? 1 : 0
  name              = "/aws/apigateway/${var.name}"
  retention_in_days = var.log_retention_days
  tags              = var.tags
}

resource "aws_apigatewayv2_api" "this" {
  name          = var.name
  protocol_type = "HTTP"
  tags          = var.tags

  dynamic "cors_configuration" {
    for_each = local.has_cors ? [1] : []
    content {
      allow_origins = var.cors_allow_origins
      allow_methods = var.cors_allow_methods
      allow_headers = var.cors_allow_headers
      max_age       = var.cors_max_age
    }
  }
}

resource "aws_apigatewayv2_integration" "this" {
  for_each = var.integrations

  api_id                 = aws_apigatewayv2_api.this.id
  integration_type       = "AWS_PROXY"
  integration_uri        = each.value.lambda_invoke_arn
  integration_method     = "POST"
  payload_format_version = "2.0"
  request_parameters = each.value.path_rewrite != null ? {
    "overwrite:path" = each.value.path_rewrite
    } : each.value.proxy_path_rewrite != null ? {
    "overwrite:path" = each.value.proxy_path_rewrite
  } : null
}

resource "aws_apigatewayv2_route" "this" {
  for_each = var.integrations

  api_id    = aws_apigatewayv2_api.this.id
  route_key = each.key
  target    = "integrations/${aws_apigatewayv2_integration.this[each.key].id}"
}

resource "aws_apigatewayv2_stage" "this" {
  api_id      = aws_apigatewayv2_api.this.id
  name        = "$default"
  auto_deploy = true
  tags        = var.tags

  dynamic "route_settings" {
    for_each = var.route_throttling
    content {
      route_key              = route_settings.key
      throttling_burst_limit = route_settings.value.burst_limit
      throttling_rate_limit  = route_settings.value.rate_limit
    }
  }

  dynamic "access_log_settings" {
    for_each = var.enable_access_logs ? [1] : []
    content {
      destination_arn = aws_cloudwatch_log_group.access_logs[0].arn
      format = jsonencode({
        requestId        = "$context.requestId"
        sourceIp         = "$context.identity.sourceIp"
        requestTime      = "$context.requestTime"
        httpMethod       = "$context.httpMethod"
        routeKey         = "$context.routeKey"
        status           = "$context.status"
        responseLength   = "$context.responseLength"
        integrationError = "$context.integrationErrorMessage"
        userAgent        = "$context.identity.userAgent"
      })
    }
  }
}

resource "aws_lambda_permission" "this" {
  for_each = local.lambda_names

  statement_id  = "AllowAPIGateway-${aws_apigatewayv2_api.this.id}"
  action        = "lambda:InvokeFunction"
  function_name = each.value
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.this.execution_arn}/*/*"
}

resource "aws_apigatewayv2_domain_name" "this" {
  count = local.has_domain ? 1 : 0

  domain_name = var.domain_name
  tags        = var.tags

  domain_name_configuration {
    certificate_arn = var.certificate_arn
    endpoint_type   = "REGIONAL"
    security_policy = "TLS_1_2"
  }
}

resource "aws_apigatewayv2_api_mapping" "this" {
  count = local.has_domain ? 1 : 0

  api_id      = aws_apigatewayv2_api.this.id
  domain_name = aws_apigatewayv2_domain_name.this[0].id
  stage       = aws_apigatewayv2_stage.this.id
}
