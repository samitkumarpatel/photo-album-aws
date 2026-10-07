output "statement_ids" {
  description = "Statement ids added to the function's resource policy."
  value       = [aws_lambda_permission.invoke_function_url.statement_id, aws_lambda_permission.invoke_function.statement_id]
}
