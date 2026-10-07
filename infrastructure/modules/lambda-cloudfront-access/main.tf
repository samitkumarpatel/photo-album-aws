# Lets one CloudFront distribution invoke the function URL through origin access control.
# AWS documents both permissions for OAC with Lambda function URLs.
resource "aws_lambda_permission" "invoke_function_url" {
  statement_id           = "AllowCloudFrontInvokeFunctionUrl"
  action                 = "lambda:InvokeFunctionUrl"
  function_name          = var.function_name
  qualifier              = var.qualifier
  principal              = "cloudfront.amazonaws.com"
  source_arn             = var.distribution_arn
  function_url_auth_type = "AWS_IAM"
}

# Scoped with lambda:InvokedViaFunctionUrl, so CloudFront cannot call the function any other way.
resource "aws_lambda_permission" "invoke_function" {
  statement_id             = "AllowCloudFrontInvokeFunction"
  action                   = "lambda:InvokeFunction"
  function_name            = var.function_name
  qualifier                = var.qualifier
  principal                = "cloudfront.amazonaws.com"
  source_arn               = var.distribution_arn
  invoked_via_function_url = true
}
