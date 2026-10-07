# A job queue with a dead-letter queue and an alarm on the dead-letter queue's depth.
# Optionally lets one S3 bucket send its event notifications to the queue.
resource "aws_sqs_queue" "dead_letter" {
  name                      = "${var.name}-dlq"
  message_retention_seconds = 1209600 # 14 days, the maximum, to leave time for investigation
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "this" {
  name                       = var.name
  visibility_timeout_seconds = var.visibility_timeout_seconds
  message_retention_seconds  = var.message_retention_seconds
  receive_wait_time_seconds  = 20
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dead_letter.arn
    maxReceiveCount     = var.max_receive_count
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "dead_letter" {
  queue_url = aws_sqs_queue.dead_letter.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.this.arn]
  })
}

data "aws_iam_policy_document" "s3_send" {
  count = var.s3_source_bucket_arn == null ? 0 : 1

  statement {
    sid       = "S3EventNotifications"
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.this.arn]

    principals {
      type        = "Service"
      identifiers = ["s3.amazonaws.com"]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [var.s3_source_bucket_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.s3_source_account_id]
    }
  }
}

resource "aws_sqs_queue_policy" "s3_send" {
  count = var.s3_source_bucket_arn == null ? 0 : 1

  queue_url = aws_sqs_queue.this.id
  policy    = data.aws_iam_policy_document.s3_send[0].json
}

resource "aws_cloudwatch_metric_alarm" "dead_letters" {
  alarm_name          = "${var.name}-dead-letters"
  alarm_description   = "Messages in ${aws_sqs_queue.dead_letter.name}: jobs failed ${var.max_receive_count} times."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.dead_letter.name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
  ok_actions          = var.alarm_actions
}
