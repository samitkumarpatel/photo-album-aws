# Photo album infrastructure

Terraform provisions the application as one reusable stack per environment. Each stack creates the Lambda functions and ECR repositories, copies a Lambda-compatible bootstrap image into ECR before Lambda creation, and creates the configured S3, DynamoDB, and SQS resources.

## Layout

```text
infrastructure/
  stacks/stack/1.0.0/       versioned, self-contained application stack
  environments/dev/         dev inputs and state; pins stack version 1.0.0
  environments/prod/        prod inputs and separate state; pins stack version 1.0.0
```

The stack version is part of its source path. Environments stay pinned to a version until deliberately updated; add a new version directory for incompatible or reviewed stack changes, then change each environment's `source` independently. The stack contains its Terraform resources directly, with no separate `modules/` tree.

Each environment hardcodes `source_image`, `source_image_tag`, resource names, and application settings in `locals.lambda`, `locals.dynamodb`, and `locals.s3`, then passes those values to the same stack. `lambda` is keyed by function name; `dynamodb` by table name; and `s3` is a list of bucket names. SQS is configured separately because S3 event processing needs queue names and object key prefixes. Dev and prod can use different values without shared name aliases.

## Image bootstrap and release

Lambda runs a Docker container image from ECR. The stack creates each ECR repository, copies its configured `source_image` to the configured `source_image_tag` when the repository is empty, and then creates Lambda from that ECR tag. The deploy host needs Terraform, AWS CLI, Docker with a running daemon, AWS credentials that can create the stack resources and push to its ECR repositories, and access to the source registry. For a private source registry, authenticate Docker to that registry before applying. The source image must match the configured Lambda architecture.

Terraform checks the repository when the bootstrap step runs and seeds it only when it contains no images. The apply identity therefore needs `ecr:DescribeImages` in addition to permission to push images. After initial creation, the release pipeline owns Lambda image updates; Terraform ignores changes to `image_uri`. Push each release image to the environment's ECR repository and call `aws lambda update-function-code` with that image URI. Repository URLs are available from the `ecr_repositories` output.

## GitHub Actions deployment

`.github/workflows/deploy-lambda.yml` builds an `amd64` image from the repository Dockerfile. Pull requests build without publishing. Pushes to `main` publish to GHCR and both dev ECR repositories, then update and wait for the dev API and worker Lambdas. `workflow_dispatch` can deploy either dev or prod. ECR release images use the commit SHA; the lifecycle rule keeps the newest ten `sha-` images and preserves the bootstrap `latest` image.

Before deployment, create the account's GitHub OIDC provider for `token.actions.githubusercontent.com` if it does not already exist. Create a deploy role with this trust relationship (replace `<account-id>`):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<account-id>:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
        "token.actions.githubusercontent.com:sub": [
          "repo:samitkumarpatel/photo-album-aws:environment:dev",
          "repo:samitkumarpatel/photo-album-aws:environment:prod"
        ]
      }
    }
  }]
}
```

Attach a permissions policy granting `ecr:GetAuthorizationToken` on `*`; `ecr:BatchCheckLayerAvailability`, `ecr:CompleteLayerUpload`, `ecr:InitiateLayerUpload`, `ecr:PutImage`, and `ecr:UploadLayerPart` on `arn:aws:ecr:eu-north-1:<account-id>:repository/photo-album-*`; and `lambda:GetFunctionConfiguration` and `lambda:UpdateFunctionCode` on `arn:aws:lambda:eu-north-1:<account-id>:function:photo-album-*`. The workflow hardcodes the deploy role ARN, so no GitHub variable is needed. Apply the prod Terraform stack before dispatching a prod deployment, and configure required reviewers on GitHub's `prod` Environment if production deployments need approval.

## Resources and permissions

- Lambda has a dedicated execution role shared by functions in that environment. It can write to its CloudWatch log groups, access the configured DynamoDB tables and indexes, read/write/delete objects in configured buckets, list those buckets, and use the configured SQS queues.
- DynamoDB uses on-demand billing. TTL is enabled when `ttl_attribute` is set. Production table deletion protection is enabled.
- S3 buckets are private, block public access, use SSE-S3 encryption, and allow the browser methods required for signed uploads and downloads. The application IAM role receives object-level access.
- SQS queues have dead-letter queues. When a queue names an S3 bucket, object-created events under its configured prefix are delivered to that queue. The worker Lambda consumes the queue.
- Production ECR repositories refuse force deletion and retain images according to the repository lifecycle policy.

## Apply

Dev stores state in the S3 backend configured in `environments/dev/terraform.tf`; prod currently uses its own local state. The dev backend bucket must already exist. When switching the existing dev workspace from local state, migrate it with `terraform init -migrate-state` from `infrastructure/environments/dev`, then apply:

```sh
cd infrastructure/environments/dev
terraform init -migrate-state
terraform apply
terraform output api_url
```

Use `infrastructure/environments/prod` for production. Configure a remote backend for prod before using it from a team or CI. Keep production state and AWS credentials isolated from dev.

The API Function URL is public and unauthenticated at the AWS layer; application authentication remains responsible for protecting API operations. S3 bucket names are globally unique. Terraform cannot delete a bucket that still contains objects, and prod DynamoDB deletion protection must be disabled deliberately before table destruction.
