data "aws_caller_identity" "current" {}
data "aws_region" "current" {}
resource "aws_iam_openid_connect_provider" "github" {
  count          = var.create_oidc_provider ? 1 : 0
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  tags = {
    Environment = "github-actions"
  }
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

locals {
  oidc_provider_arn = var.create_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn

  project_prefixes = toset([
    for repo in var.repos : trimsuffix(split("/", repo)[1], "-aws")
  ])

  trusted_subjects = flatten([
    for repo in var.repos : flatten([
      [for environment in var.environments : "repo:${repo}:environment:${environment}"],
      [for environment in var.environments : "repo:${split("/", repo)[0]}@*/${split("/", repo)[1]}@*:environment:${environment}"],
    ])
  ])

  ecr_resources = flatten([
    for project_prefix in local.project_prefixes : [
      for environment in var.environments :
      "arn:aws:ecr:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:repository/${project_prefix}-${environment}-*"
    ]
  ])

  lambda_resources = flatten([
    for project_prefix in local.project_prefixes : [
      for environment in var.environments :
      "arn:aws:lambda:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:function:${project_prefix}-${environment}-*"
    ]
  ])

  frontend_bucket_names = flatten([
    for project_prefix in local.project_prefixes : [
      for environment in var.environments : "${project_prefix}-${environment}-${data.aws_caller_identity.current.account_id}-frontend"
    ]
  ])
}

data "aws_iam_policy_document" "assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = local.trusted_subjects
    }
  }
}

resource "aws_iam_role" "this" {
  name               = var.name
  assume_role_policy = data.aws_iam_policy_document.assume_role.json
  tags = {
    Environment  = "github-actions"
    Repositories = join(",", sort(tolist(var.repos)))
  }

  lifecycle {
    create_before_destroy = true
  }
}

data "aws_iam_policy_document" "deploy" {
  statement {
    sid       = "EcrAuthorization"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "PublishEnvironmentImages"
    actions = [
      "ecr:BatchGetImage",
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = local.ecr_resources
  }

  statement {
    sid = "UpdateEnvironmentLambdas"
    actions = [
      "lambda:GetFunction",
      "lambda:GetFunctionConfiguration",
      "lambda:UpdateFunctionCode",
    ]
    resources = local.lambda_resources
  }

  statement {
    sid = "ListFrontendBuckets"
    actions = [
      "s3:GetBucketLocation",
      "s3:ListBucket",
    ]
    resources = [for bucket in local.frontend_bucket_names : "arn:aws:s3:::${bucket}"]
  }

  statement {
    sid = "DeployFrontendFiles"
    actions = [
      "s3:DeleteObject",
      "s3:GetObject",
      "s3:PutObject",
    ]
    resources = [for bucket in local.frontend_bucket_names : "arn:aws:s3:::${bucket}/*"]
  }

  statement {
    sid       = "InvalidateFrontendDistributions"
    actions   = ["cloudfront:CreateInvalidation"]
    resources = ["arn:aws:cloudfront::${data.aws_caller_identity.current.account_id}:distribution/*"]
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "deploy"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.deploy.json
}
