# The REST API as a GraalVM native container-image Lambda function, published behind an alias.
# Lambda Web Adapter maps Function URL invocations to the Spring HTTP server in the image.
data "aws_partition" "current" {}

locals {
  table_index_arns = [for name in var.dynamodb_index_names : "${var.dynamodb_table_arn}/index/${name}"]
  media_object_arn = "${var.media_bucket_arn}/${var.media_object_prefix}*"
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

# Least privilege: exactly the calls DynamoDbAlbumRepository, S3MediaStorage and the URL signers make.
# Presigned upload and download URLs act with this role, so they need the same object permissions.
data "aws_iam_policy_document" "permissions" {
  statement {
    sid = "AlbumData"
    actions = [
      "dynamodb:GetItem",
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:DeleteItem",
      "dynamodb:Query",
      "dynamodb:BatchWriteItem",
      "dynamodb:ConditionCheckItem",
      "dynamodb:TransactWriteItems",
    ]
    resources = concat([var.dynamodb_table_arn], local.table_index_arns)
  }

  statement {
    sid       = "MediaObjects"
    actions   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
    resources = [local.media_object_arn]
  }

  # Deleting an album or photo removes every object under its originals/ and derived/ prefixes.
  statement {
    sid       = "ListMedia"
    actions   = ["s3:ListBucket"]
    resources = [var.media_bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${var.media_object_prefix}*"]
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
  architectures = [var.architecture] # must match the platform the image was built for
  memory_size   = var.memory_mb
  timeout       = var.timeout_seconds

  # A new image URI publishes a new version of the native executable.
  publish = true

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

resource "aws_lambda_function_url" "this" {
  function_name      = aws_lambda_function.this.function_name
  qualifier          = aws_lambda_alias.live.name
  authorization_type = "AWS_IAM"
  invoke_mode        = "BUFFERED"
}
