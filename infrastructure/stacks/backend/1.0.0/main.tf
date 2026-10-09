# The application stack provisions Lambda functions and their ECR images, plus their data and event resources.
data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.region

  default_source_image     = "ghcr.io/samitkumarpatel/aws-lambda-fullstack:latest"
  default_source_image_tag = "latest"
  lambda_seed_images = {
    for name, function in var.lambda : name => {
      source_image     = coalesce(function.source_image, local.default_source_image)
      source_image_tag = coalesce(function.source_image_tag, local.default_source_image_tag)
    }
  }
}
