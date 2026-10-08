provider "aws" {
  region = "eu-north-1"

  default_tags {
    tags = {
      Project     = "photo-album"
      Environment = "github-actions"
      ManagedBy   = "terraform"
    }
  }
}
