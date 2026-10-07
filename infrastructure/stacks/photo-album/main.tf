# The photo album application: Lambda functions (with their ECR repositories), S3 buckets,
# DynamoDB tables and SQS queues. One file per service; environments call this stack once with plain values.
data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.region
}
