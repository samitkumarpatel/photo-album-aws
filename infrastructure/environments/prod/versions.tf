terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Separate local state from dev. Configure an S3 backend before team or CI use.
  backend "local" {}
}
