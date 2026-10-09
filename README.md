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
- `POST /api/albums/from-selection` — create a private album with independent copies of 1–100 ready photos or videos; body: `{"name":"Favorites","description":"","items":[{"albumId":"…","photoId":"…"}]}`. A failed copy removes the new album and its media.
- `POST /api/albums/{albumId}/uploads` — start an upload (`{"filename":"beach.jpg","contentType":"image/jpeg","size":2483112}`, 100 MB maximum); returns a URL to `PUT` the raw file to (S3 presigned, or an API route in memory mode)
- `POST /api/albums/{albumId}/uploads/{photoId}/complete` — confirm the file arrived; the photo moves to `PROCESSING`, then `READY` once thumbnails exist
- `POST /api/albums/{albumId}/photos` — deprecated multipart upload; too large for Lambda's 6 MB request limit
- `GET /api/albums/{albumId}/photos` — list media; each photo has `status`, `width`, `height`, `takenAt` and `urls` (`thumbnail`, `display`, `original`, `download`)
- `GET /api/albums/{albumId}/photos/{photoId}?size=thumbnail|display|original` — the media itself; streamed in memory mode, a redirect to a signed URL in S3 mode
- `PUT /api/albums/{albumId}/photos/{photoId}` — save an edited image as a new version, keeping the earlier originals
- `DELETE /api/albums/{albumId}/photos/{photoId}` — delete media, including unfinished uploads
- `POST /api/albums/{albumId}/shares` — create a view-only token link with an expiry (`{"amount":2,"unit":"WEEKS","label":"Family","recipient":"person@example.com"}`; units: `HOURS`, `DAYS`, `WEEKS`, `MONTHS`, `YEARS`)
- `GET /api/albums/{albumId}/shares` — active links, soonest expiry first; owner album responses also include these in `shares`. Public album responses do not expose the owner's share tokens.
- `DELETE /api/albums/{albumId}/shares/{token}` — revoke a link before expiry. New visits fail immediately. Newly issued shared media URLs expire within five minutes, capped by the link expiry; downloaded or already displayed content cannot be recalled. URLs issued before this change keep their original lifetime.
- `GET /api/shared/{token}/status` — validate a link without issuing media URLs; the shared frontend checks every 30 seconds and refreshes media URLs every four minutes while visible.
- `GET /api/shared/{token}` and `GET /api/shared/{token}/photos/{photoId}` — read an album through a valid share link

## Background removal and collages

Open a photo's editor and choose **Background → Remove background**. Portrait matting uses [Xenova/MODNet](https://huggingface.co/Xenova/modnet) (Apache 2.0) through Transformers.js in a Web Worker. Photos stay on the device during inference; first use downloads model weights from Hugging Face and the WebAssembly runtime from jsDelivr. This model works best with people and is not a general object segmentation model. Removal can be canceled, restored, undone and redone. Choose transparency (automatically saves PNG), a solid color, background blur, or an uploaded replacement image. Restore and erase brushes refine the mask; brush strokes follow crop, rotation and flip changes. Save as a copy or replace the photo. The preview uses a checkerboard for transparency.

For collages, use **Select photos & videos** on Photos or an album page, select **2–9 ready photos**, and choose **Create collage**. Choose Grid, Mosaic, Featured photo beside or above, Side by side or Stacked, or start from a template. Drag photos to frame them, zoom, add captions and a title, and customize spacing, borders and rounded corners. Save a new JPEG to the chosen album at 2400 or 4000 pixels on its longest side. Source photos are retained. Videos can be included in album selections, but cannot be included in collages.

## Creative features

- **Saved edits and history:** reopen the editor to adjust saved settings against the retained source, instead of repeatedly applying edits to exported pixels. The viewer's clock button previews versions, compares with the current image, and restores a version as a new edit. Save as copy creates an independent source and recipe. History metadata starts with edits made after this feature was added; older images start at their earliest recorded version.
- **Batch editing:** select ready photos and choose **Batch edit photos**. Apply a preset, resize, or watermark to up to 100 photos, saving copies or replacing with history retained. Processing is sequential, shows per-photo results, supports stopping after the current photo and retrying unfinished photos. Videos are excluded.
- **Album presentation:** open **Album options → Album presentation** for a cover photo, classic/dark/warm/minimal theme, brand name, logo, slideshow timing and presentation watermark. Owner and shared galleries use the same presentation. Slideshow supports photos and videos, pause, previous and next. Presentation watermarks are visual overlays; use photo/batch watermarks to stamp exported images.
- **Sharing management:** the **Sharing** page lists albums with active links. Links have optional labels and recipient emails, editable expiry, copy/open/invite actions and revocation. Email invitation opens a draft in the user's email app; the application does not send email or restrict a link to the named recipient.

Creative metadata uses `EXTRA#` records within existing DynamoDB album partitions. Versioned originals remain in object storage; larger recipes, background assets and masks are stored under `recipes/{albumId}/{photoId}/vN.json` (2 MB per recipe). Photo/album deletion also cleans associated recipes and version records. In memory mode all data is lost on restart. No new AWS resources are required.

Security additions (account sign-in, ownership and password-protected links) and subscription enforcement remain deferred until after the photo and album work, as requested for development.

### Creative API

- `GET /api/albums/{albumId}/photos/{photoId}/edit` — retained source URL, source version and current recipe.
- `GET /api/albums/{albumId}/photos/{photoId}/versions` — current and archived version previews.
- `GET /api/albums/{albumId}/photos/{photoId}/versions/{version}/media` — original pixels for a version.
- `POST /api/albums/{albumId}/photos/{photoId}/versions/{version}/restore` — restore as a new version.
- `POST /api/albums/{albumId}/photos/{photoId}/copy-source` — create an independent editable source (`{"version":1}`).
- `POST /api/albums/{albumId}/photos/{photoId}/replacement` — start a staged presigned replacement with filename/contentType/size and optional `editRecipe` including `baseVersion`.
- `POST /api/albums/{albumId}/photos/{photoId}/replacement/{uploadId}/{version}/complete` — promote uploaded pixels and persist the recipe; same request body as the intent.
- `GET` / `PUT /api/albums/{albumId}/presentation` — gallery theme, cover photo, logo, brand name, watermark and slideshow timing.
- `PATCH /api/albums/{albumId}/shares/{token}` — set a future `expiresAt`, label and recipient. Expired or revoked links require a new link.

## S3 media storage

In the frontend, use **Select photos & videos** on Photos or an album page, choose items, and select **Create album**. New albums stay private until a share link is created. Album cards and pages show Private or Shared, the active link count, and a way to manage expiry and revoke links. The photo editor includes captions with color, size and position controls, blur, and Portrait, Landscape and Golden hour presets alongside its existing adjustments and crop tools.

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
