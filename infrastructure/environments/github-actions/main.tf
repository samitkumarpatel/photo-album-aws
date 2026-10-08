data "aws_caller_identity" "current" {}

locals {
  region = "eu-north-1"
}

module "github_actions" {
  source = "../../stacks/github-actions/1.0.0"

  name              = "photo-album-github-actions-deploy"
  repository        = var.github_repository
  environment_names = var.github_environments
  account_id        = data.aws_caller_identity.current.account_id
  region            = local.region
}
