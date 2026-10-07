# Single table for albums, photos and share links.
# Must stay in sync with src/test/resources/localstack/init-aws.sh and DynamoDbAlbumRepository.
resource "aws_dynamodb_table" "this" {
  name         = var.name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"
  range_key    = "sk"

  attribute {
    name = "pk"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  attribute {
    name = "gsi1pk"
    type = "S"
  }

  attribute {
    name = "gsi1sk"
    type = "S"
  }

  # Lists an owner's albums. Sparse: only album items carry gsi1pk.
  global_secondary_index {
    name            = "gsi1"
    projection_type = "ALL"

    key_schema {
      attribute_name = "gsi1pk"
      key_type       = "HASH"
    }

    key_schema {
      attribute_name = "gsi1sk"
      key_type       = "RANGE"
    }
  }

  # Share links expire on their own; the app still checks expiresAt.
  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = var.point_in_time_recovery
  }

  deletion_protection_enabled = var.deletion_protection

  # DynamoDB always encrypts at rest with an AWS owned key. Passing a KMS key switches to a
  # customer managed key, which adds KMS request charges.
  dynamic "server_side_encryption" {
    for_each = var.kms_key_arn == null ? [] : [var.kms_key_arn]
    content {
      enabled     = true
      kms_key_arn = server_side_encryption.value
    }
  }
}
