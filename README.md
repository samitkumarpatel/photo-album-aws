# Stillroom photo albums

A React single-page app served by Spring Boot, with a REST API for albums and media.

The AWS deployment plan lives in [docs/aws-architecture-plan.md](docs/aws-architecture-plan.md).
Work still to do is tracked in [docs/pending-tasks.md](docs/pending-tasks.md).
The Terraform that provisions it lives in [infrastructure/README.md](infrastructure/README.md).

## Run locally

Requirements: Java 25, Node.js, and npm.

```sh
cd frontend
npm install
npm run build
cd -
./mvnw spring-boot:run
```

Frontend source, npm manifests, and Vite config live under the root `frontend/` folder. Vite writes only its compiled bundle into `src/main/resources/static`, which Spring Boot packages and serves. For UI development with hot reload, run `npm run dev` from `frontend/` in one terminal and `./mvnw spring-boot:run` from the project root in another; Vite proxies `/api` to port 8080.

## REST endpoints

- `GET /api/albums` — list albums with photo summaries
- `POST /api/albums` — create an album (`{"name":"Weekend away","description":"..."}`)
- `GET /api/albums/{albumId}` — album details
- `PATCH /api/albums/{albumId}` — rename an album or change its description (`{"name":"Lisbon"}`)
- `DELETE /api/albums/{albumId}` — delete an album, its stored media, and its share links
- `POST /api/albums/{albumId}/uploads` — start an upload (`{"filename":"beach.jpg","contentType":"image/jpeg","size":2483112}`, 100 MB maximum); returns a URL to `PUT` the raw file to (S3 presigned, or an API route in memory mode)
- `POST /api/albums/{albumId}/uploads/{photoId}/complete` — confirm the file arrived; the photo moves to `PROCESSING`, then `READY` once thumbnails exist
- `POST /api/albums/{albumId}/photos` — deprecated multipart upload; too large for Lambda's 6 MB request limit
- `GET /api/albums/{albumId}/photos` — list media; each photo has `status`, `width`, `height`, `takenAt` and `urls` (`thumbnail`, `display`, `original`, `download`)
- `GET /api/albums/{albumId}/photos/{photoId}?size=thumbnail|display|original` — the media itself; streamed in memory mode, a redirect to a signed URL in S3 mode
- `PUT /api/albums/{albumId}/photos/{photoId}` — save an edited image as a new version, keeping the earlier originals
- `DELETE /api/albums/{albumId}/photos/{photoId}` — delete media, including unfinished uploads
- `POST /api/albums/{albumId}/shares` — create a view-only token link with an expiry (`{"amount":2,"unit":"WEEKS"}`; units: `HOURS`, `DAYS`, `WEEKS`, `MONTHS`, `YEARS`)
- `GET /api/shared/{token}` and `GET /api/shared/{token}/photos/{photoId}` — read an album through a valid share link

## S3 media storage

S3 access goes through [Spring Cloud AWS](https://docs.awspring.io/spring-cloud-aws/docs/4.0.0/reference/html/index.html), which auto-configures the AWS SDK v2 clients. Browsers upload straight to S3 with presigned `PUT` URLs locked to the file's size and type, and read media through signed URLs: CloudFront when `PHOTO_ALBUM_CDN_DOMAIN`, `PHOTO_ALBUM_CDN_KEY_PAIR_ID` and `PHOTO_ALBUM_CDN_PRIVATE_KEY` are set, otherwise S3 presigned `GET`. The bucket needs a CORS rule allowing `PUT`, `GET` and `HEAD` from the site's origin. Credentials come from the SDK default provider chain, so prefer an attached IAM role in AWS and a named AWS profile for local work.

```sh
export PHOTO_ALBUM_STORAGE_MODE=s3
export PHOTO_ALBUM_S3_BUCKET=your-private-bucket
export PHOTO_ALBUM_S3_PREFIX=photo-album
export AWS_REGION=eu-north-1
./mvnw spring-boot:run
```

`PHOTO_ALBUM_STORAGE_MODE=memory` is the default and needs no AWS account. `AWS_REGION` maps to `spring.cloud.aws.region.static` and defaults to `eu-north-1`. Any other `spring.cloud.aws.*` property, such as a custom endpoint, can be set the usual Spring Boot way.

For S3 mode, the application role needs `s3:PutObject`, `s3:GetObject`, and `s3:DeleteObject` on `arn:aws:s3:::your-private-bucket/photo-album/*`, and `s3:ListBucket` on the bucket for that prefix. No public ACL is set; keep S3 Block Public Access enabled.

Uploaded originals are processed into WebP thumbnail (400 px) and display (2048 px) images, and their size and capture date are read from EXIF. Locally this runs inside the API (`PHOTO_ALBUM_PROCESSING_MODE=in-process`, the default). On AWS, S3 events go through SQS to a worker Lambda (`processing.S3EventWorkerHandler`) and the API sets `PHOTO_ALBUM_PROCESSING_MODE=events`.

## DynamoDB album data

Albums, photo records and share links live behind `AlbumRepository`. By default they stay in process memory and are lost on restart. To keep them in DynamoDB:

```sh
export PHOTO_ALBUM_DATA_MODE=dynamodb
export PHOTO_ALBUM_TABLE=photo-album
./mvnw spring-boot:run
```

The table must already exist. It uses string keys `pk` and `sk`, a global secondary index `gsi1` on `gsi1pk` and `gsi1sk` with all attributes projected, on-demand billing, and TTL on the `ttl` attribute. `src/test/resources/localstack/init-aws.sh` shows the exact definition, and [docs/aws-architecture-plan.md](docs/aws-architecture-plan.md) explains the key design.

The application role needs `dynamodb:GetItem`, `PutItem`, `UpdateItem`, `DeleteItem`, `Query`, `BatchWriteItem`, and `ConditionCheckItem` on the table and its `gsi1` index.

## Testing with LocalStack

Integration tests run against real AWS APIs in a throw-away [LocalStack](https://www.localstack.cloud/) container, started by Testcontainers. Docker or a compatible engine must be running.

```sh
./mvnw test
```

- `TestcontainersConfiguration` starts `localstack/localstack:3` and registers it with `@ServiceConnection`, so every Spring Cloud AWS client points at the container.
- The `localstack` profile switches media to S3 and album data to DynamoDB, and enables S3 path-style access.
- `src/test/resources/localstack/init-aws.sh` creates the bucket and the DynamoDB table when LocalStack is ready. Add new buckets, tables, or queues there; the application never creates AWS resources itself.
- Annotate a test with `@Import(TestcontainersConfiguration.class)` and `@ActiveProfiles("localstack")` to run it against LocalStack.

To run the whole app locally against LocalStack instead of memory or real AWS:

```sh
./mvnw spring-boot:test-run
```
