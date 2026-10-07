# ---- Images: one ECR repository per function ----
resource "aws_ecr_repository" "this" {
  for_each = var.functions

  name                 = each.key
  image_tag_mutability = "MUTABLE"
  force_delete         = true # images are rebuilt by the pipeline

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the newest 10 images for rollbacks"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 10 }
      action       = { type = "expire" }
    }]
  })
}

# Lambda pulls with its service principal; scoped to functions in this account.
resource "aws_ecr_repository_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "LambdaPull"
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
      Condition = { StringLike = { "aws:sourceArn" = "arn:aws:lambda:${local.region}:${local.account_id}:function:*" } }
    }]
  })
}

# Copies the bootstrap image into the new repository, once, so the function has something to run.
# Runs on the machine applying Terraform: needs docker and the AWS CLI, and a docker login to the
# source registry if the image is private.
resource "terraform_data" "bootstrap_image" {
  for_each = var.functions

  triggers_replace = [aws_ecr_repository.this[each.key].repository_url]

  provisioner "local-exec" {
    command = <<-EOT
      set -e
      aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$${TARGET%%/*}"
      docker pull --platform "linux/$ARCH" "$SOURCE"
      docker tag "$SOURCE" "$TARGET"
      docker push "$TARGET"
    EOT
    environment = {
      REGION = local.region
      ARCH   = each.value.architecture == "x86_64" ? "amd64" : "arm64"
      SOURCE = each.value.image
      TARGET = "${aws_ecr_repository.this[each.key].repository_url}:bootstrap"
    }
  }
}

# ---- Permissions: one role for all functions, limited to the stack's own resources ----
resource "aws_iam_role" "lambda" {
  name = "${var.name}-lambda"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

data "aws_iam_policy_document" "lambda" {
  statement {
    sid       = "Logs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = [for g in aws_cloudwatch_log_group.this : "${g.arn}:*"]
  }

  dynamic "statement" {
    for_each = length(aws_dynamodb_table.this) > 0 ? [1] : []
    content {
      sid = "Tables"
      actions = [
        "dynamodb:GetItem", "dynamodb:BatchGetItem", "dynamodb:Query", "dynamodb:Scan",
        "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:DeleteItem", "dynamodb:BatchWriteItem",
        "dynamodb:ConditionCheckItem", "dynamodb:TransactGetItems", "dynamodb:TransactWriteItems",
      ]
      resources = flatten([for t in aws_dynamodb_table.this : [t.arn, "${t.arn}/index/*"]])
    }
  }

  dynamic "statement" {
    for_each = length(aws_s3_bucket.this) > 0 ? [1] : []
    content {
      sid       = "Objects"
      actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
      resources = [for b in aws_s3_bucket.this : "${b.arn}/*"]
    }
  }

  dynamic "statement" {
    for_each = length(aws_s3_bucket.this) > 0 ? [1] : []
    content {
      sid       = "ListBuckets"
      actions   = ["s3:ListBucket"]
      resources = [for b in aws_s3_bucket.this : b.arn]
    }
  }

  dynamic "statement" {
    for_each = length(aws_sqs_queue.this) > 0 ? [1] : []
    content {
      sid = "Queues"
      actions = [
        "sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage",
        "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes", "sqs:GetQueueUrl",
      ]
      resources = [for q in aws_sqs_queue.this : q.arn]
    }
  }
}

resource "aws_iam_role_policy" "lambda" {
  name   = "${var.name}-lambda"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.lambda.json
}

# ---- Functions ----
resource "aws_cloudwatch_log_group" "this" {
  for_each = var.functions

  name              = "/aws/lambda/${each.key}"
  retention_in_days = 14
}

resource "aws_lambda_function" "this" {
  for_each = var.functions

  function_name = each.key
  role          = aws_iam_role.lambda.arn
  package_type  = "Image"
  image_uri     = "${aws_ecr_repository.this[each.key].repository_url}:bootstrap"
  architectures = [each.value.architecture]
  memory_size   = each.value.memory
  timeout       = each.value.timeout

  dynamic "image_config" {
    for_each = each.value.handler == null ? [] : [each.value.handler]
    content {
      command = [image_config.value]
    }
  }

  environment {
    variables = each.value.environment
  }

  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.this[each.key].name
  }

  # The pipeline owns the image after creation (aws lambda update-function-code --image-uri ...).
  lifecycle {
    ignore_changes = [image_uri]
  }

  depends_on = [aws_iam_role_policy.lambda, aws_ecr_repository_policy.this, terraform_data.bootstrap_image]
}

# ---- Triggers ----
resource "aws_lambda_event_source_mapping" "sqs" {
  for_each = { for name, f in var.functions : name => f if f.sqs_trigger != null }

  event_source_arn        = aws_sqs_queue.this[each.value.sqs_trigger].arn
  function_name           = aws_lambda_function.this[each.key].arn
  batch_size              = 5
  function_response_types = ["ReportBatchItemFailures"] # retry only the messages that failed
}

# ---- Public URLs ----
locals {
  public_functions = { for name, f in var.functions : name => f if f.public_url }
}

resource "aws_lambda_function_url" "this" {
  for_each = local.public_functions

  function_name      = aws_lambda_function.this[each.key].function_name
  authorization_type = "NONE"
}

# A public function URL needs both permissions.
resource "aws_lambda_permission" "url" {
  for_each = local.public_functions

  statement_id           = "AllowPublicFunctionUrl"
  action                 = "lambda:InvokeFunctionUrl"
  function_name          = aws_lambda_function.this[each.key].function_name
  principal              = "*"
  function_url_auth_type = "NONE"
}

resource "aws_lambda_permission" "url_invoke" {
  for_each = local.public_functions

  statement_id             = "AllowPublicInvokeViaFunctionUrl"
  action                   = "lambda:InvokeFunction"
  function_name            = aws_lambda_function.this[each.key].function_name
  principal                = "*"
  invoked_via_function_url = true
}
