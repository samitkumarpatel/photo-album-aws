# Private registry for the API container image that Lambda runs.
data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

resource "aws_ecr_repository" "this" {
  name                 = var.name
  image_tag_mutability = var.immutable_tags ? "IMMUTABLE" : "MUTABLE"
  force_delete         = var.force_delete

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# Lambda pulls the image with its service principal. Granting it here (rather than on each
# execution role) also lets Lambda re-pull after a function has gone inactive.
data "aws_iam_policy_document" "lambda_pull" {
  statement {
    sid     = "LambdaECRImageRetrievalPolicy"
    actions = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "aws:sourceArn"
      values   = ["arn:${data.aws_partition.current.partition}:lambda:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:function:*"]
    }
  }
}

resource "aws_ecr_repository_policy" "this" {
  repository = aws_ecr_repository.this.name
  policy     = data.aws_iam_policy_document.lambda_pull.json
}

resource "aws_ecr_lifecycle_policy" "this" {
  repository = aws_ecr_repository.this.name
  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Remove untagged images after a day"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep the newest tagged images for rollbacks"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.keep_images
        }
        action = { type = "expire" }
      },
    ]
  })
}
