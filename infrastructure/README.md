# Stillroom infrastructure (Terraform)

Terraform for the AWS deployment described in [docs/aws-architecture-plan.md](../docs/aws-architecture-plan.md): DynamoDB for album data, S3 for media and the SPA, the REST API as a container-image Lambda function with SnapStart, one CloudFront distribution in front of everything, and a cost budget.

## Layout

```
infrastructure/
  modules/                      small, reusable building blocks; no provider blocks, no environment values
    budget/                     monthly AWS Budgets alert by email
    cloudfront-signing-key/     generated key pair, CloudFront public key and key group for signed media URLs
    cloudfront-site/            distribution, origin access controls, SPA route and media prefix functions
    dynamodb-table/             single table: pk/sk, gsi1 (gsi1pk/gsi1sk), on-demand, TTL on "ttl"
    ecr-repository/             private image registry for the API, lifecycle and Lambda pull policy
    lambda-api/                 container-image function, SnapStart, alias, IAM function URL, role, logs
    job-queue/                  SQS queue, dead-letter queue, alarm, optional S3 send policy
    lambda-cloudfront-access/   lets one distribution invoke the function URL
    lambda-worker/              container-image function, SnapStart, alias, SQS event source with batch item failures
    s3-bucket/                  private, encrypted bucket with optional lifecycle and CORS rules
    s3-cloudfront-read-policy/  bucket policy: read only through one distribution, HTTPS only
  stacks/
    photo-album/                the whole application, composed from the modules; inputs only
  environments/
    dev/                        root module: providers, backend, and one call to the stack with literal values
```

How the layers fit together:

- **Modules** each do one thing and know nothing about environments.
- **The stack** wires modules into the application: names, permissions between resources, and the environment variables the API reads. It exposes inputs such as memory size or domain names, but holds no environment values.
- **An environment** is the only place with concrete values. `environments/dev/main.tf` sets every stack input as a literal, so there are no `.tfvars` files to keep in sync.

"Stack" here means this composition layer. It is not HCP Terraform Stacks (`.tfstack.hcl` and `.tfdeploy.hcl` files); this setup runs with the plain Terraform CLI.

## What gets created

| Resource | Name in dev | Notes |
| --- | --- | --- |
| DynamoDB table | `photo-album-dev-data` | Matches `src/test/resources/localstack/init-aws.sh`. Point-in-time recovery and deletion protection are inputs, off in dev. Encrypted at rest with the AWS owned key unless a KMS key is passed. |
| Media bucket | `photo-album-dev-media-<account-id>` | Private, SSE-S3. Lifecycle: abort incomplete uploads after 1 day, expire `trash/` after 30 days, move `originals/` and the app's current `photo-album/` prefix to Intelligent-Tiering after 90 days. CORS allows `PUT` and `GET` from the site for presigned uploads. |
| SPA bucket | `photo-album-dev-site-<account-id>` | Private; CloudFront reads it through origin access control. |
| ECR repository | `photo-album-dev-api` | Scan on push, immutable tags, keeps the newest 10 images, removes untagged images after a day. Its policy lets Lambda pull images. |
| Lambda function | `photo-album-dev-api` | Container image, arm64, 2048 MB, SnapStart on published versions, alias `live`. The function URL uses `AWS_IAM` auth, so only CloudFront can call it. The role can use only the table, its `gsi1` index, and objects under the media prefix. |
| CloudFront | one distribution | Default route serves the SPA, `/api/*` goes to the function URL without caching, and `/media/*` serves the media bucket when enabled, accepting only signed URLs. |
| Signing key | `photo-album-dev-media` | Generated RSA key pair; CloudFront trusts the public key through a key group, and the API gets the private key as `PHOTO_ALBUM_CDN_PRIVATE_KEY`. The private key is in Terraform state. |
| Processing queue | `photo-album-dev-processing` | Receives S3 `ObjectCreated` events for `photo-album/originals/`. Visibility timeout is six times the worker timeout; after 3 failed attempts a message moves to `-dlq`, which raises an alarm. |
| Worker function | `photo-album-dev-worker` | Same image as the API with the handler `processing.S3EventWorkerHandler`. Reads `originals/`, writes `derived/`, updates the table. 2048 MB, 120 s, 2 GB `/tmp`, at most 2 concurrent runs in dev. |
| Alerts topic | `photo-album-dev-alerts` | SNS topic for the dead-letter alarm; the budget emails are subscribed and must confirm. |
| Budget | `photo-album-dev-monthly` | Emails at 80% and 100% of actual spend and at 100% of forecast. |

The stack sets these environment variables on the function, matching `src/main/resources/application.yaml`:

| Variable | Value |
| --- | --- |
| `PHOTO_ALBUM_DATA_MODE` | `dynamodb` |
| `PHOTO_ALBUM_TABLE` | the table name |
| `PHOTO_ALBUM_STORAGE_MODE` | `s3` |
| `PHOTO_ALBUM_S3_BUCKET` | the media bucket |
| `PHOTO_ALBUM_S3_PREFIX` | `photo-album` |
| `PHOTO_ALBUM_PROCESSING_MODE` | `events`, because S3 events drive the worker |
| `PHOTO_ALBUM_CDN_DOMAIN`, `PHOTO_ALBUM_CDN_KEY_PAIR_ID`, `PHOTO_ALBUM_CDN_PRIVATE_KEY` | Set once the media route is on and its domain is known (see below). Without them the API hands out S3 presigned URLs. |

The worker gets the same data and storage variables, plus `JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED` for the native WebP encoder.

`AWS_REGION` is not set: Lambda provides it, and the app maps it to `spring.cloud.aws.region.static`.

## Prerequisites

- Terraform 1.10 or newer, the AWS CLI, and Docker with Buildx.
- AWS credentials for the target account, for example `aws login` or a named profile.
- Before applying, replace the placeholder budget email in `environments/dev/main.tf`.

## Deploying

All commands run from `infrastructure/environments/dev`.

### 1. Initialise

```sh
terraform init
```

### 2. First time only: create the image repository

Lambda needs the image to exist before the function can be created, so create the repository on its own first:

```sh
terraform apply -target=module.photo_album.module.api_repository
```

### 3. Build and push the API image

The repository-root `Dockerfile` builds on the AWS Java 25 base image (`public.ecr.aws/lambda/java:25`), which supports SnapStart without extra hooks. A Spring Boot executable jar does not run as-is on that image, because its classes sit under `BOOT-INF/`, so the image holds the layout the Java runtime expects instead: classes in `${LAMBDA_TASK_ROOT}` and dependency jars in `${LAMBDA_TASK_ROOT}/lib/`. `./mvnw package` writes that layout to `target/lambda/`; the Dockerfile runs that build itself in a first stage, so no local build is needed. The SPA is not part of the image; CloudFront serves it from S3 (step 5).

The image's CMD is the API handler, `net.samitkumar.photo_album_aws.lambda.StreamLambdaHandler::handleRequest`. The worker function uses the same image with its CMD overridden to `net.samitkumar.photo_album_aws.processing.S3EventWorkerHandler::handleRequest`.

```sh
cd ../../..                                    # repository root
./mvnw verify                                  # optional: tests (LocalStack needs Docker); the image build skips them

REPO=$(terraform -chdir=infrastructure/environments/dev output -raw api_repository_url)
TAG=0.0.1                                      # must equal api_image_tag in environments/dev/main.tf
aws ecr get-login-password --region eu-north-1 | docker login --username AWS --password-stdin "${REPO%%/*}"
docker buildx build --platform linux/arm64 --provenance=false -t "$REPO:$TAG" --push .
```

- `--platform linux/arm64` must match `lambda_architecture`. The Maven stage runs on the build machine's own platform and the final stage only copies files, so building for either architecture needs no emulation.
- `--provenance=false` produces a single-image manifest. Lambda rejects the multi-entry manifest that Buildx attestations create.
- Tags are immutable, and Terraform only updates the function when the image URI changes. Use a new tag for every release, such as the git commit hash, and update `api_image_tag` to match.

### 4. Apply everything

```sh
cd infrastructure/environments/dev
terraform plan
terraform apply
```

Each new image tag publishes a new function version, SnapStart takes its snapshot, and the `live` alias moves to it.

### 5. Deploy the SPA

The frontend build currently writes to `src/main/resources/static`.

```sh
(cd ../../../frontend && npm ci && npm run build)
SPA_BUCKET=$(terraform output -raw spa_bucket)
DISTRIBUTION=$(terraform output -raw cloudfront_distribution_id)
aws s3 sync ../../../src/main/resources/static "s3://$SPA_BUCKET" --delete
aws cloudfront create-invalidation --distribution-id "$DISTRIBUTION" --paths "/*"
```

`terraform output site_url` prints the address.

## Remote state

`environments/dev` uses local state so `terraform init` works immediately. For shared or CI use:

1. Create a private, versioned S3 bucket for state once, by hand or with a small separate configuration.
2. Replace `backend "local" {}` in `environments/dev/versions.tf` with the commented `backend "s3"` block. `use_lockfile = true` uses S3-native locking, so no DynamoDB lock table is needed.
3. Run `terraform init -migrate-state`.

## Adding an environment

1. Copy `environments/dev` to, for example, `environments/prod`.
2. Change the literals in `main.tf`: `environment = "prod"`, deletion protection and point-in-time recovery on, `force_destroy_buckets = false`, a real budget, and a domain with its certificate if wanted.
3. Give it its own state key or bucket.

Custom domains need an ACM certificate in `us-east-1`, passed as `acm_certificate_arn` together with `domain_aliases`. DNS records pointing at `cloudfront_domain_name` are managed outside this configuration for now.

## Things to know before the first deploy

- **Request bodies through CloudFront need a hash header.** With origin access control in front of a Lambda function URL, AWS requires `PUT` and `POST` requests to include the SHA-256 of the body in an `x-amz-content-sha256` header, because Lambda does not accept unsigned payloads. This is stated in the CloudFront guide under "Restrict access to an AWS Lambda function URL origin". The frontend's JSON requests (create album, rename, share) must add this header. `PATCH` most likely needs it too. `GET` and `DELETE` without a body do not.
- **Signed media URLs need a second apply without a custom domain.** The API must know the public domain to sign URLs, and CloudFront only assigns it on the first apply. Apply once, set `media_cdn_domain` in `environments/dev/main.tf` to the `cloudfront_domain_name` output, and apply again. With `domain_aliases` set, the first alias is used and one apply is enough. Until then the API hands out S3 presigned URLs, which work but stop when the Lambda role's temporary credentials expire.
- **Confirm the alert subscriptions.** Each address in `budget_alert_emails` gets an SNS confirmation email for the dead-letter alarm.
- **Edits still go through Lambda.** "Replace original" in the editor uploads through the API, so edited images over 6 MB fail until it moves to a presigned upload. New uploads already go straight to S3.
- **SnapStart and uniqueness.** Anything created during start-up, such as random seeds or cached credentials, is shared by every environment restored from the snapshot. The AWS SDK and the Java base image handle their own state; review the app's own start-up code against the SnapStart uniqueness guidance.

## Validation

`terraform fmt -recursive -check` passes, and `terraform validate` passes for every module, the stack, and `environments/dev` with AWS provider 6.67.0 and TLS provider 4.x. Nothing has been planned or applied against an AWS account yet.

## Next steps

These later phases from the architecture plan are not in this configuration yet:

- **Amazon Cognito** user pool and app client for sign-in.
- **AI image service** as a container Lambda behind its own SQS queue.
- **Video poster frames:** an ffmpeg Lambda layer for the worker.
- **Private key in a secret store:** move the signing key to Secrets Manager once the app can read it from there.
- **DNS** records for custom domains, and a state-bootstrap configuration.
