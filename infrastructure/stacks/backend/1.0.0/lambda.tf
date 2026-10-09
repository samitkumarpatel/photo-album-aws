# ---- Images: one ECR repository per function ----
resource "aws_ecr_repository" "this" {
  for_each = var.lambda

  name                 = each.key
  image_tag_mutability = "MUTABLE"
  force_delete         = each.value.force_delete

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
      description  = "Keep the newest 10 SHA-tagged release images"
      selection    = { tagStatus = "tagged", tagPrefixList = ["sha-"], countType = "imageCountMoreThan", countNumber = 10 }
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
      Condition = { StringEquals = { "aws:sourceArn" = "arn:aws:lambda:${local.region}:${local.account_id}:function:${each.key}" } }
    }]
  })
}

# Seeds an empty ECR repository with a bootstrap image so Lambda can be created on the first apply.
# Runs on the Terraform host, which needs Docker, AWS CLI, and source-registry access.
resource "terraform_data" "bootstrap_image" {
  for_each = var.lambda

  triggers_replace = [aws_ecr_repository.this[each.key].repository_url, local.lambda_seed_images[each.key].source_image, local.lambda_seed_images[each.key].source_image_tag, each.value.architecture]

  provisioner "local-exec" {
    command = <<-EOT
      set -e
      command -v aws >/dev/null || { echo "AWS CLI is required to bootstrap Lambda images" >&2; exit 1; }
      command -v docker >/dev/null || { echo "Docker is required to bootstrap Lambda images" >&2; exit 1; }

      IMAGE_COUNT=$(aws ecr describe-images \
        --repository-name "$REPOSITORY" \
        --region "$REGION" \
        --query 'length(imageDetails)' \
        --output text)

      if [ "$IMAGE_COUNT" = "0" ]; then
        aws ecr get-login-password --region "$REGION" \
          | docker login --username AWS --password-stdin "$${TARGET%%/*}"
        docker pull --platform "linux/$ARCH" "$SOURCE"
        docker tag "$SOURCE" "$TARGET"
        docker push "$TARGET"
      else
        echo "ECR repository already has $IMAGE_COUNT image(s); skipping bootstrap seed."
      fi
    EOT
    environment = {
      REGION     = local.region
      REPOSITORY = aws_ecr_repository.this[each.key].name
      ARCH       = each.value.architecture == "x86_64" ? "amd64" : "arm64"
      SOURCE     = local.lambda_seed_images[each.key].source_image
      TARGET     = "${aws_ecr_repository.this[each.key].repository_url}:${local.lambda_seed_images[each.key].source_image_tag}"
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
  for_each = var.lambda

  name              = "/aws/lambda/${each.key}"
  retention_in_days = 14
}

resource "aws_lambda_function" "this" {
  for_each = var.lambda

  function_name = each.key
  role          = aws_iam_role.lambda.arn
  package_type  = "Image"
  image_uri     = "${aws_ecr_repository.this[each.key].repository_url}:${local.lambda_seed_images[each.key].source_image_tag}"
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
  for_each = { for name, f in var.lambda : name => f if f.sqs_trigger != null }

  event_source_arn        = aws_sqs_queue.this[each.value.sqs_trigger].arn
  function_name           = aws_lambda_function.this[each.key].arn
  batch_size              = 5
  function_response_types = ["ReportBatchItemFailures"] # retry only the messages that failed
}

# ---- HTTP API ----
locals {
  http_integrations = merge({}, [
    for function_name, function in var.lambda : merge(
      {
        for public_path, lambda_path in function.http_router : "ANY ${public_path}" => {
          lambda_invoke_arn    = aws_lambda_function.this[function_name].invoke_arn
          lambda_function_name = function_name
          path_rewrite         = lambda_path
        }
      },
      {
        for public_path, lambda_path in function.http_router : "ANY ${public_path}/{proxy+}" => {
          lambda_invoke_arn    = aws_lambda_function.this[function_name].invoke_arn
          lambda_function_name = function_name
          proxy_path_rewrite   = "${lambda_path}/$${request.path.proxy}"
        }
      }
    )
  ]...)
}

module "api_gateway_http" {
  source = "../../../modules/api_gateway_http/0.0.1"

  name               = "${var.name}-http"
  integrations       = local.http_integrations
  cors_allow_origins = var.api_cors_allow_origins
  enable_access_logs = var.api_enable_access_logs
  log_retention_days = var.api_log_retention_days
  route_throttling   = var.api_route_throttling
  domain_name        = var.api_domain_name
  certificate_arn    = var.api_certificate_arn
}
