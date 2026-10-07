output "policy_json" {
  description = "The bucket policy document."
  value       = data.aws_iam_policy_document.this.json
}
