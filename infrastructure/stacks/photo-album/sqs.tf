locals {
  queues = { for q in var.sqs : q.name => q }

  # Lambda requires the visibility timeout to cover the consuming function's timeout; AWS recommends six times it.
  visibility_timeout = {
    for name, q in local.queues : name => 6 * max(30, [for f in var.functions : f.timeout if f.sqs_trigger == name]...)
  }
}

resource "aws_sqs_queue" "dead_letter" {
  for_each = local.queues

  name                      = "${each.key}-dlq"
  message_retention_seconds = 1209600 # 14 days, to leave time for investigation
}

resource "aws_sqs_queue" "this" {
  for_each = local.queues

  name                       = each.key
  visibility_timeout_seconds = local.visibility_timeout[each.key]
  receive_wait_time_seconds  = 20

  # After 3 failed attempts a message moves to the dead-letter queue.
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dead_letter[each.key].arn
    maxReceiveCount     = 3
  })
}

# Lets the configured bucket (in this account) send its events to the queue.
resource "aws_sqs_queue_policy" "s3" {
  for_each = { for name, q in local.queues : name => q if q.s3_bucket != null }

  queue_url = aws_sqs_queue.this[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "s3.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.this[each.key].arn
      Condition = {
        ArnEquals    = { "aws:SourceArn" = aws_s3_bucket.this[each.value.s3_bucket].arn }
        StringEquals = { "aws:SourceAccount" = local.account_id }
      }
    }]
  })
}
