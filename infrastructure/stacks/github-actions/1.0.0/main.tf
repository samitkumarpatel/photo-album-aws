resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_policy_document" "assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        for environment in var.environment_names :
        "repo:${var.repository}:environment:${environment}"
      ]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name               = var.name
  assume_role_policy = data.aws_iam_policy_document.assume_role.json
}

data "aws_iam_policy_document" "deploy_permissions" {
  statement {
    sid       = "EcrAuthorization"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "PublishEnvironmentImages"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = [
      for environment in var.environment_names :
      "arn:aws:ecr:${var.region}:${var.account_id}:repository/photo-album-${environment}-*"
    ]
  }

  statement {
    sid     = "UpdateEnvironmentLambdas"
    actions = ["lambda:GetFunctionConfiguration", "lambda:UpdateFunctionCode"]
    resources = [
      for environment in var.environment_names :
      "arn:aws:lambda:${var.region}:${var.account_id}:function:photo-album-${environment}-*"
    ]
  }
}

resource "aws_iam_role_policy" "deploy_permissions" {
  name   = "photo-album-lambda-deploy"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.deploy_permissions.json
}
