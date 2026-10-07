# Photo album infrastructure (Terraform)

Lambda (container images in ECR), S3, DynamoDB and SQS.

## Layout

```
infrastructure/
  stacks/photo-album/      the reusable stack, one file per service
    variables.tf           the four inputs: functions, s3_buckets, dynamodb, sqs
    lambda.tf              ECR repository + bootstrap image per function, functions, shared role, triggers, URLs
    s3.tf                  private buckets, CORS, S3 -> SQS notifications
    dynamodb.tf            on-demand tables with indexes and TTL
    sqs.tf                 queues, each with a dead-letter queue
  environments/dev/        providers, backend, and one call to the stack
```

## The stack's input

`environments/dev/main.tf` is the whole picture:

```hcl
module "photo_album" {
  source = "../../stacks/photo-album"
  name   = "photo-album-dev"

  functions = {
    "photo-album-dev-api"    = { image = local.bootstrap_image, public_url = true, environment = {...} }
    "photo-album-dev-worker" = { image = local.bootstrap_image, handler = "...S3EventWorkerHandler::handleRequest", sqs_trigger = local.queue }
  }

  s3_buckets = [local.bucket]

  dynamodb = {
    (local.table) = { hash_key = "pk", range_key = "sk", indexes = { gsi1 = { hash_key = "gsi1pk", range_key = "gsi1sk" } }, ttl_attribute = "ttl" }
  }

  sqs = [
    { name = local.queue, s3_bucket = local.bucket, s3_prefix = "photo-album/originals/" },
  ]
}
```

Names are literal: what you write is what AWS gets.

| Function field | Default | Meaning |
| --- | --- | --- |
| `image` | required | Bootstrap image, copied into the function's ECR repository when the function is created |
| `handler` | image's CMD | Overrides the image's CMD, for example to run another handler from the same image |
| `architecture` | `arm64` | Must match the image |
| `memory`, `timeout` | `1024`, `30` | MB, seconds |
| `environment` | `{}` | Environment variables |
| `public_url` | `false` | Public function URL, no auth |
| `sqs_trigger` | none | A queue from `sqs` that invokes the function, with per-message failure reporting |

## How images flow

1. **Terraform creates a function.** It creates an ECR repository with the function's name, then copies `image` (from GHCR) into it as `:bootstrap`. Lambda then starts from that image.
2. **The pipeline deploys.** It pushes a new image to the repository and points the function at it:
   ```sh
   docker push <account>.dkr.ecr.<region>.amazonaws.com/photo-album-dev-api:<sha>
   aws lambda update-function-code --function-name photo-album-dev-api \
     --image-uri <account>.dkr.ecr.<region>.amazonaws.com/photo-album-dev-api:<sha>
   ```
   Repeat for `photo-album-dev-worker`. `terraform output ecr_repositories` lists the repositories.
3. **Terraform leaves the image alone afterwards.** It ignores `image_uri` changes, so a later `terraform apply` never rolls back what the pipeline deployed.

The copy runs once per repository, on the machine running `terraform apply`. That machine needs `docker` and the AWS CLI. If the GHCR package is private, run `docker login ghcr.io` first.

## Deploy the infrastructure

```sh
cd infrastructure/environments/dev
terraform init
terraform apply
terraform output api_url
```

Things the stack works out on its own:

- **Access:** one IAM role for all functions. It can use every table (including indexes), bucket and queue in the stack, and nothing else.
- **ECR:** the newest 10 images are kept. Lambda in this account may pull from the repositories.
- **Dead-letter queues:** each queue `x` gets `x-dlq`. A message moves there after 3 failed attempts.
- **Visibility timeout:** six times the timeout of the slowest function the queue triggers.
- **Notifications:** setting `s3_bucket` on a queue sends that bucket's `ObjectCreated` events under `s3_prefix` to the queue.

## Notes

- **The API URL is public.** Nothing sits in front of it; the app has to handle its own authentication.
- **Buckets can't be destroyed while they hold objects.** Empty them first. ECR repositories are deleted together with their images.
- **Remote state:** `environments/dev/versions.tf` uses local state. To share it, switch to the commented `backend "s3"` block and run `terraform init -migrate-state`.
- **Another environment:** copy `environments/dev` and change the names.
