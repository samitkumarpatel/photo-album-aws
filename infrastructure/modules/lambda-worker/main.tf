# A queue-driven worker as a container-image Lambda function with SnapStart. SQS delivers batches
# to the published alias, and the function reports failed messages individually.
locals {
  table_index_arns = [for name in var.dynamodb_index_names : "${var.dynamodb_table_arn}/index/${name}"]
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/aws/lambda/${var.name}"
  retention_in_days = var.log_retention_days
}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "this" {
  name               = "${var.name}-role"
  assume_role_policy = data.aws_iam_policy_document.assume.json
}

data "aws_iam_policy_document" "permissions" {
  statement {
    sid       = "Jobs"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:ChangeMessageVisibility"]
    resources = [var.queue_arn]
  }

  # The worker loads a photo and writes it back; it never creates or deletes albums.
  statement {
    sid       = "AlbumData"
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:Query"]
    resources = concat([var.dynamodb_table_arn], local.table_index_arns)
  }

  dynamic "statement" {
    for_each = length(var.media_read_prefixes) == 0 ? [] : [1]
    content {
      sid       = "ReadMedia"
      actions   = ["s3:GetObject"]
      resources = [for prefix in var.media_read_prefixes : "${var.media_bucket_arn}/${prefix}*"]
    }
  }

  dynamic "statement" {
    for_each = length(var.media_write_prefixes) == 0 ? [] : [1]
    content {
      sid       = "WriteMedia"
      actions   = ["s3:PutObject", "s3:DeleteObject"]
      resources = [for prefix in var.media_write_prefixes : "${var.media_bucket_arn}/${prefix}*"]
    }
  }

  statement {
    sid       = "Logs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.this.arn}:*"]
  }
}

resource "aws_iam_role_policy" "this" {
  name   = "${var.name}-permissions"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.permissions.json
}

resource "aws_lambda_function" "this" {
  function_name = var.name
  description   = var.description
  role          = aws_iam_role.this.arn
  package_type  = "Image"
  image_uri     = var.image_uri
  architectures = [var.architecture]
  memory_size   = var.memory_mb
  timeout       = var.timeout_seconds

  image_config {
    command = var.image_command
  }

  ephemeral_storage {
    size = var.ephemeral_storage_mb
  }

  publish = true

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = var.environment
  }

  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.this.name
  }

  depends_on = [aws_iam_role_policy.this]
}

resource "aws_lambda_alias" "live" {
  name             = var.alias_name
  function_name    = aws_lambda_function.this.function_name
  function_version = aws_lambda_function.this.version
}

# Targets the alias, so messages run on the SnapStart-enabled published version.
resource "aws_lambda_event_source_mapping" "queue" {
  event_source_arn                   = var.queue_arn
  function_name                      = aws_lambda_alias.live.arn
  batch_size                         = var.batch_size
  maximum_batching_window_in_seconds = var.batching_window_seconds
  function_response_types            = ["ReportBatchItemFailures"]

  scaling_config {
    maximum_concurrency = var.maximum_concurrency
  }
}
