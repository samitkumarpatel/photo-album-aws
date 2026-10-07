output "arn" {
  description = "Queue ARN."
  value       = aws_sqs_queue.this.arn
}

output "url" {
  description = "Queue URL."
  value       = aws_sqs_queue.this.id
}

output "name" {
  description = "Queue name."
  value       = aws_sqs_queue.this.name
}

output "dead_letter_arn" {
  description = "Dead-letter queue ARN."
  value       = aws_sqs_queue.dead_letter.arn
}

output "dead_letter_url" {
  description = "Dead-letter queue URL, for inspecting and redriving failed jobs."
  value       = aws_sqs_queue.dead_letter.id
}

# S3 validates the destination when the notification is created, so it must wait for the policy.
output "s3_send_policy_id" {
  description = "Id of the queue policy that lets the bucket send; depend on it before creating the bucket notification."
  value       = one(aws_sqs_queue_policy.s3_send[*].id)
}
