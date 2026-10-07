# Stillroom on AWS: architecture and delivery plan

Status: **Draft for review**
Last updated: 2026-10-07 (phase 1 and phase 3 code done; worker and signed media in Terraform)

This document plans how to move Stillroom from a single Spring Boot process to a serverless AWS deployment. It covers the target architecture, the data model, the code changes in this repository, and the delivery phases.

## 1. Goals and constraints

- Store photos and videos in **Amazon S3**.
- Run the REST API as an **AWS Lambda** function. Cold starts are acceptable for now.
- Host the React SPA on **CloudFront or Cloudflare**, whichever costs less.
- Use **SQS or SNS** for background and scheduled work.
- Keep running costs close to zero at hobby scale, and make costs predictable as usage grows.

### Starting point

| Area | Today |
| --- | --- |
| API | Spring Boot 4.1, Java 25, one process |
| Album, photo and share data | `AlbumRepository` with two implementations: in memory (default) and DynamoDB (`PHOTO_ALBUM_DATA_MODE=dynamodb`) |
| Media bytes | S3; the API hands out signed URLs (CloudFront, S3 presigned, or API routes locally) |
| Uploads | Browser PUTs straight to S3 with a presigned URL, then calls "complete" |
| Thumbnails | WebP thumbnail and display sizes from the processing worker |
| Users | No accounts or sign-in |
| SPA | Built into the Spring Boot jar and served by it |

## 2. Key decisions

### 2.1 App data store: DynamoDB, with S3 Tables for analytics only

**Decision: DynamoDB**, implemented in this repository. The initial idea was to keep album and photo data in Amazon S3 Tables; the reasons for choosing DynamoDB instead are below.

S3 Tables stores Apache Iceberg tables. Applications reach them through Athena, Spark or Iceberg client libraries such as the S3 Tables Iceberg REST endpoint. AWS positions it for analytics data such as purchase logs, sensor streams and ad impressions. For per-request app data this causes problems:

- **Latency.** Opening an album becomes an Athena query or an Iceberg scan, which takes about a second or more.
- **Write conflicts.** Each insert writes new data files and commits a new snapshot. Two uploads at the same moment conflict and must retry.
- **Cold starts.** The Iceberg Java client and its dependencies make Lambda cold starts noticeably slower.
- **Cost shape.** Athena bills per query with a minimum scan size, which suits a few large queries rather than many tiny ones.

DynamoDB fits the access pattern. It is serverless, billed per request, answers in single-digit milliseconds, and its always-free tier covers a hobby-sized app.

S3 Tables remains part of the plan for **analytics**: view counts, popular photos and storage reports, fed from an event stream in phase 6.

All data access goes through the `AlbumRepository` interface, so the storage choice stays contained in one class.

### 2.2 Hosting: one CloudFront distribution

The SPA costs almost nothing on either CloudFront or Cloudflare. The deciding cost is **media delivery**:

- Data transfer from S3 to CloudFront is free.
- The CloudFront always-free tier includes 1 TB of data transfer and 10 million requests per month.
- If Cloudflare served media from S3, AWS would bill internet egress for every gigabyte leaving S3.

So one CloudFront distribution serves the SPA, the media and the API on a single domain. A single origin also removes the need for CORS.

CloudFront also offers flat-rate plans, starting with a $0 Free plan. AWS accounts still on the AWS Free Tier are not eligible for them, and the pay-as-you-go free tier is larger. Start on pay-as-you-go and revisit flat-rate plans when traffic grows.

### 2.3 Background work: SQS, plus EventBridge Scheduler for timed jobs

- **SQS** for job queues. It gives retries, visibility timeouts and dead-letter queues.
- **SNS** only when one event must reach several queues, for example "photo uploaded" feeding both thumbnails and AI tagging.
- **EventBridge Scheduler** for time-based jobs.
- **DynamoDB TTL and S3 lifecycle rules** replace most scheduled clean-up, such as expiring share links and emptying the trash, at no extra cost.

### 2.4 Uploads and downloads bypass Lambda

A buffered Lambda invocation is limited to 6 MB for both request and response. Videos can be up to 100 MB. Therefore:

- **Uploads** go straight from the browser to S3 using a presigned PUT URL. The browser first tells the API the file's size and type. The API rejects anything over 100 MB or of the wrong type, then signs the URL with that exact `Content-Length` and `Content-Type`. S3 refuses any upload that does not match.
- Presigned POST with a size-range policy is not an option: the AWS SDK for Java v2 has no presigned POST support (checked in SDK 2.54.3).
- **Downloads** come from CloudFront with short-lived signed URLs. The API only issues the URLs.

### 2.5 API runtime: Spring Boot as a container-image Lambda function with SnapStart

- **Decision: deploy the API as a container image** from a private Amazon ECR repository, built on the AWS base image `public.ecr.aws/lambda/java:25`.
- SnapStart supports container images built on the AWS Java base images (11 and later, x86_64 and arm64) without extra hooks. SnapStart has no extra charge for Java.
- Every release uses a new, immutable image tag. A new tag publishes a new function version, and an alias moves to it.
- Adapt Spring Boot with the AWS Serverless Java Container or Spring Cloud Function. Confirm Spring Boot 4 support for the chosen adapter at implementation time.
- The project already includes the GraalVM native image plugin. A native build on a custom runtime is the fallback if cold starts become a problem.
- Expose the function through a **Lambda function URL** behind CloudFront with origin access control, so only CloudFront can invoke it. This avoids API Gateway request charges.

### 2.6 AWS client library: Spring Cloud AWS on top of the AWS SDK v2

**Decision: use Spring Cloud AWS**, adopted in this repository with version 4.1.1.

Spring Cloud AWS does not replace the AWS SDK; it auto-configures SDK v2 clients from `spring.cloud.aws.*` properties and adds Spring-style helpers. Where a helper is missing, the same auto-configured SDK clients are used directly.

| Need | Spring Cloud AWS | Plain AWS SDK v2 |
| --- | --- | --- |
| Client setup for region, credentials, endpoint | Auto-configured from properties | Hand-written builders per client |
| S3 upload, download, delete, signed GET | `S3Template` | `S3Client` and `S3Presigner` |
| Signed PUT with exact size | Use the auto-configured `S3Presigner` bean; `S3Template#createSignedPutURL` cannot set `Content-Length` | `S3Presigner` |
| DynamoDB | `DynamoDbTemplate` on the enhanced client, table name prefixes | Enhanced client by hand |
| SQS in a long-running app | `@SqsListener`, `SqsTemplate` | Polling code by hand |
| SQS events inside Lambda | Not applicable; Lambda's event source mapping delivers messages to a handler | Same |
| Tests against LocalStack | `@ServiceConnection` for `LocalStackContainer` | Manual endpoint wiring |

Notes:

- **Compatibility.** The Spring Cloud AWS 4.x line is built against Spring Boot 4.0. This project runs Spring Boot 4.1.1, and its LocalStack integration tests pass with Spring Cloud AWS 4.1.1. Re-run them when upgrading either.
- **Large uploads through the current API** use `S3Client#putObject` with a known length, because the auto-configured `S3Template#upload` buffers the whole file in memory. This path goes away once uploads use presigned URLs.
- **Lambda cold start.** Auto-configuration adds a little startup work. SnapStart snapshots an initialised context, which absorbs most of it.
- **Native image.** Spring Cloud AWS has experimental GraalVM support. DynamoDB entities then need static table schemas.

### 2.7 Testing: Testcontainers and LocalStack

- Integration tests start LocalStack with Testcontainers. Spring Cloud AWS's `@ServiceConnection` support points every AWS client at the container.
- A LocalStack ready hook creates the bucket and the DynamoDB table with its index and TTL, and later queues. It mirrors the Terraform definitions in `infrastructure/`.
- `./mvnw spring-boot:test-run` starts the app against LocalStack for local development.
- `LocalStackContainer` reports its region from the `DEFAULT_REGION` variable, and `@ServiceConnection` hands that region to the clients. The container sets it to the same region the ready hook uses; otherwise the clients look for the table in `us-east-1`.
- The image is pinned to `localstack/localstack:3`, because newer `latest` images require a licence token. LocalStack's free edition does not cover every API, so services such as Cognito are tested with mocks or in a real AWS sandbox.

### 2.8 Sign-in: Amazon Cognito

- A Cognito user pool handles sign-up, sign-in and password reset.
- The API validates Cognito JWTs with Spring Security's resource server support.
- Share links stay public and token-based, so viewers do not need accounts.

## 3. Target architecture

```
                          ┌───────────────────────────────┐
 Browser ──HTTPS──▶ CloudFront (one domain, WAF optional) │
                          │  /api/*   ─▶ Lambda function URL (OAC) ─▶ DynamoDB
                          │  /media/* ─▶ S3 media bucket (OAC, signed URLs)
                          │  /*       ─▶ S3 SPA bucket (OAC) + SPA rewrite function
                          └───────────────────────────────┘
 Browser ──presigned PUT───▶ S3 media bucket (originals/)
                                   │ ObjectCreated
                                   ▼
                                SQS queue ──▶ Worker Lambda ──▶ thumbnails/, DynamoDB status
                                   │ failures
                                   ▼
                              Dead-letter queue
```

### Components

| Component | Service | Notes |
| --- | --- | --- |
| CDN and single entry point | CloudFront | Three behaviors: API, media, SPA |
| SPA routing | CloudFront Function | Rewrites app routes to `index.html` |
| REST API | Lambda container image (Java 25 base image), SnapStart | Function URL with origin access control |
| API image | Amazon ECR | Immutable tags, scan on push, keeps recent images for rollback |
| App data | DynamoDB, on-demand | Single-table design, TTL for expiry |
| Media | S3 private bucket | Originals, derivatives, edited versions |
| SPA files | S3 private bucket | Deployed by CI |
| Upload processing | S3 event to SQS to Lambda | Thumbnails, metadata, video poster |
| Scheduled jobs | EventBridge Scheduler | Only where TTL or lifecycle rules cannot help |
| Users | Cognito user pool | JWT validated in the API |
| Infrastructure as code | Terraform, in `infrastructure/` | Modules, one stack, one folder per environment |
| Cost control | AWS Budgets | Alert from day one |

### S3 media bucket layout

```
originals/{albumId}/{photoId}/v{n}.{ext}     original upload and edited versions
derived/{albumId}/{photoId}/v{n}/thumb.webp  about 400 px, for grids
derived/{albumId}/{photoId}/v{n}/display.webp about 2048 px, for the viewer
trash/...                                    removed after 30 days by a lifecycle rule
```

All keys sit under the configured storage prefix (`photo-album/` by default). Derivatives are versioned with their original, so every edit gets new, immutable, cache-safe URLs. A video's poster frame will become its thumbnail and display images once the worker has ffmpeg.

Lifecycle rules:

- Move originals that have not been read for 90 days to S3 Intelligent-Tiering.
- Abort incomplete multipart uploads after 1 day.
- Expire `trash/` after 30 days.

## 4. Data model (DynamoDB single table)

Implemented in `DynamoDbAlbumRepository`. One on-demand table, `photo-album` by default, holds every item type.

| Item | pk | sk | gsi1pk | gsi1sk | Other attributes |
| --- | --- | --- | --- | --- | --- |
| Album | `ALBUM#{albumId}` | `META` | `OWNER#{owner}` | `ALBUM#{albumId}` | entityType, albumId, name, description, createdAt |
| Photo | `ALBUM#{albumId}` | `PHOTO#{photoId}` | | | entityType, photoId, filename, contentType, size, objectKey, uploadedAt, editedAt |
| Share link | `SHARE#{token}` | `META` | | | entityType, albumId, expiresAt, ttl |

Why this shape:

- **An album and its photos share a partition.** "Album with photos" is one strongly consistent query, and deleting an album deletes one partition.
- **`gsi1` lists an owner's albums.** It is sparse: only album items carry `gsi1pk`, so photos and shares never appear in it.
- **Until sign-in exists, every album belongs to the owner `default`.** With Cognito the owner becomes the user's id; no other key changes, and existing albums can be re-owned by rewriting `gsi1pk`.
- **Share links expire automatically.** DynamoDB TTL is enabled on `ttl`, which holds the expiry in epoch seconds. TTL deletion can lag by a day or two, so the API still checks `expiresAt`.

Consistency rules:

- Creating an album fails if the id already exists.
- Adding a photo is a transaction with a condition check on the album, so a photo can never be added to an album deleted a moment earlier.
- Replacing a photo only succeeds if the photo still exists. Renaming only succeeds if the album still exists.
- Deleting an album batch-deletes its partition 25 items at a time and retries anything DynamoDB reports as unprocessed. Share links of a deleted album answer "not found" and are removed when first used or by TTL.

Photo lifecycle (implemented):

- Photo items carry `status` (`UPLOADING`, `PROCESSING`, `READY`, `FAILED`; a missing value means ready), `width` and `height` after EXIF orientation, `takenAt`, and the `thumbnailKey` and `displayKey` of the derivatives.
- **The `UPLOADING` photo item is the upload intent.** It carries `ttl` = upload time + 1 hour, removed when the photo leaves `UPLOADING`. Owner listings hide expired intents and delete them lazily.
- Status changes are conditional updates, for example `UPLOADING` → `PROCESSING` only if the photo is still uploading.

Planned additions:

- **Timeline index** for the cross-album photo view: a second index keyed by owner and `takenAt`, once EXIF extraction exists.

Known cost to revisit: listing albums reads every album's partition, because the album list currently includes all photos. A summary listing with only cover photos and counts removes that once the frontend no longer needs every photo up front.

## 5. API changes

| Endpoint | Change |
| --- | --- |
| `GET /api/albums`, `GET /api/albums/{id}` | **Done.** Each photo has `status`, `width`, `height`, `takenAt` and `urls: {thumbnail, display, original}`; storage keys are no longer returned |
| `POST /api/albums/{id}/uploads` | **Done.** Takes filename, size and type; returns a presigned PUT URL locked to that size and type, and creates the photo as `UPLOADING` |
| `POST /api/albums/{id}/uploads/{photoId}/complete` | **Done.** Checks the object exists with the right size and moves the photo to `PROCESSING` |
| `POST /api/albums/{id}/photos` | **Deprecated**, still works locally; Lambda's 6 MB limit rules it out on AWS |
| `GET /api/albums/{id}/photos/{photoId}?size=` | **Done.** Redirects to a signed URL in S3 mode; streams locally |
| `PUT /api/albums/{id}/photos/{photoId}` | Writes `v{n+1}` and keeps older originals for revert (**done**); still a multipart upload through the API, to become a presigned upload in phase 4 |
| `DELETE` endpoints | Move objects to `trash/` and mark items deleted; TTL removes them later |
| `GET /api/shared/{token}` | **Done.** Signed URLs expire no later than the share link; uploading and failed photos are hidden |
| All owner endpoints | Require a Cognito JWT and check album ownership |

`OAS.yaml` and the README are updated alongside each change.

## 6. Code changes in this repository

### Backend

- ~~Introduce an `AlbumRepository` interface with in-memory and DynamoDB implementations.~~ **Done:** DynamoDB uses the enhanced client auto-configured by Spring Cloud AWS.
- ~~Add an upload service that creates presigned PUT URLs with the exact size and content type signed in.~~ **Done** (`upload/`), with an HMAC-signed API route as the local stand-in.
- ~~Add a CloudFront URL signer for media links.~~ **Done** (`media/`): CloudFront canned-policy URLs rounded up to the hour for cacheability, S3 presigned GET without a CDN, API routes locally.
- Add Spring Security resource server configuration for Cognito.
- ~~Add the Lambda handler using the chosen Spring adapter.~~ **Done:** `lambda.StreamLambdaHandler` (function URL, HTTP API v2 events) and a `lambda` profile. The repo-root `Dockerfile` builds one image on `public.ecr.aws/lambda/java:25` for the API and the worker.
- ~~Remove `SpaController`.~~ **Done differently:** it stays for local runs and is switched off by `spring.application.spa.enabled=false` in the `lambda` profile.
- ~~Add a worker module for SQS events.~~ **Done** (`processing/`): magic-byte validation (a mismatch means `FAILED`, original kept), EXIF dimensions and `takenAt`, WebP derivatives via webp-imageio with a JPEG fallback, a lean non-web Spring context, and `S3EventWorkerHandler` with batch item failures. HEIC, AVIF, video and images over 100 MP become ready without derivatives. Locally `spring.application.processing.mode=in-process` runs it in the API; on AWS it is `events`.

### Frontend

- ~~Upload through the presigned PUT URL with progress, then show a "processing" state until the photo is ready.~~ **Done:** three parallel uploads with progress, cancel and retry; processing tiles with polling; multipart fallback for older backends.
- ~~Use thumbnail URLs in grids and display URLs in the viewer.~~ **Done:** the viewer switches to the original when zoom needs more pixels; the editor loads the original.
- ~~Send `x-amz-content-sha256` on API requests with a body.~~ **Done** in `frontend/src/ui/api.js`; it needs a secure context (https or localhost).
- Add sign-in screens backed by Cognito.
- Build output goes to a deploy folder for S3 instead of the Spring Boot static folder.

### Local development

- Keep running everything locally with in-memory storage, as today.
- Integration tests and `./mvnw spring-boot:test-run` use LocalStack through Testcontainers for both S3 and DynamoDB; SQS joins the same container when it arrives.

## 7. Delivery phases

### Phase 1: prepare the code locally

No AWS resources required.

- ~~Repository interface and DynamoDB implementation.~~ **Done**, with one contract test suite run against both implementations, the DynamoDB run against LocalStack.
- ~~Presigned upload and signed media URL services behind interfaces, with local stand-ins.~~ **Done.**
- ~~Frontend upload flow, processing state, and derivative URLs.~~ **Done.**
- ~~Tests for the new services.~~ **Done**, including a LocalStack round trip through a presigned PUT with signature checks on.

**Done when** the app runs locally with the new upload flow and all tests pass.

### Phase 2: infrastructure with Terraform

The Terraform lives in [`infrastructure/`](../infrastructure/README.md): reusable modules, a `photo-album` stack that composes them, and `environments/dev`, which passes literal values to the stack.

- ~~Buckets, DynamoDB table, ECR repository, Lambda API from a container image with SnapStart, function URL with origin access control.~~ **Written and validated**, not yet applied.
- ~~CloudFront distribution with the API, SPA and media behaviors and the SPA rewrite function.~~ **Written and validated.**
- ~~AWS Budgets alert.~~ **Written and validated.**
- ~~Lambda handler in the application, and a Dockerfile for the image.~~ **Done.**
- ~~Frontend sends `x-amz-content-sha256` on requests with a body.~~ **Done.**
- ~~Signed media: generated key pair, CloudFront key group on `/media/*`, CDN settings on the API.~~ **Written and validated.** Without a custom domain it takes a second apply, because the API needs the distribution's domain.
- Cognito user pool and app client.
- First deployment of API and SPA.

**Done when** the deployed app supports sign-in, album creation, upload and viewing.

### Phase 3: background processing

- ~~S3 event notifications to SQS, worker Lambda, dead-letter queue and alarm.~~ **Written and validated** in Terraform (`job-queue`, `lambda-worker`), not yet applied.
- ~~Thumbnails and display sizes as WebP, EXIF extraction.~~ **Done.** Video poster frames wait for an ffmpeg Lambda layer.
- ~~Grids switch to thumbnails.~~ **Done.**

**Done when** new uploads show thumbnails within seconds and failures land in the dead-letter queue with an alarm.

### Phase 4: album features

- Non-destructive editing: keep originals, store versions and the edit recipe, add "Revert to original".
- Recently Deleted with 30-day TTL and lifecycle clean-up.
- Multi-select for bulk delete, move and download.
- Favourites and album cover selection.

### Phase 5: AI image service

- Python container Lambda behind an SQS job queue. A job record in DynamoDB tracks status, and the SPA polls it.
- First features: background removal and object eraser.
- Later: upscaling, HEIC and RAW decoding, metadata-preserving export, content search.
- Move to ECS or AWS Batch with GPUs only if CPU Lambda becomes too slow.

### Phase 6: social features and analytics

- Likes, reactions and comments on shared albums, stored in DynamoDB.
- Notifications through SNS and Amazon SES.
- Usage events streamed into S3 Tables and queried with Athena for reports.

## 8. Cost notes

At hobby scale most services stay inside free tiers. The main recurring cost is **S3 storage** of originals and derivatives.

Watch these as usage grows:

- **CloudFront data transfer** beyond the free tier, driven by full-size downloads and video.
- **Lambda duration** of image processing workers and the AI service.
- **DynamoDB on-demand requests** if pages make many reads; batch reads keep this low.
- **Cognito** monthly active users beyond the free tier.

Thumbnails and display-size images are the biggest single saving, because grids stop downloading originals.

## 9. Risks and open questions

| Risk or question | Mitigation |
| --- | --- |
| Album listing reads every album partition | Add a summary listing before albums number in the hundreds |
| Item size limit of 400 KB | Not a concern: photos are separate items, and edit recipes stay small |
| Spring Boot 4 support in Lambda adapters | `aws-serverless-java-container-springboot4` exists and is used by another project of ours on Boot 4.1; verify before phase 2 |
| Spring Cloud AWS officially targets Boot 4.0 | LocalStack integration tests catch breakage on upgrades |
| Cold starts with SnapStart | Measure in phase 2; native image if needed |
| Request bodies through CloudFront to Lambda need an `x-amz-content-sha256` header | Add it in the frontend's API helper; uploads move to presigned S3 PUT anyway |
| Lambda's 6 MB request and response limit | Presigned uploads and the `/media/*` route replace streaming through the API |
| Large video processing exceeds Lambda limits | Use AWS Elemental MediaConvert for transcoding if needed |
| Signed URL expiry versus caching | Use URL lifetimes of hours, not minutes, and cache derivatives at the edge |
| Data migration from in-memory storage | None needed; current data is not persisted |
| Face grouping and privacy law | Keep any face features opt-in and documented |
