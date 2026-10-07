terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Local state so `terraform init` works straight away. For shared use, create a versioned,
  # private S3 bucket once (by hand or a small bootstrap config) and switch to:
  #
  # backend "s3" {
  #   bucket       = "photo-album-terraform-state-<account-id>"
  #   key          = "photo-album/dev/terraform.tfstate"
  #   region       = "eu-north-1"
  #   encrypt      = true
  #   use_lockfile = true # S3-native locking, Terraform 1.10+; no DynamoDB lock table needed
  # }
  backend "local" {}
}
