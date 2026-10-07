# Pending tasks

Status snapshot: 2026-10-07. 88 tests pass; `npm run build` passes; upload → WebP thumbnails → download smoke-tested locally. Tracks progress on [aws-architecture-plan.md](aws-architecture-plan.md). Update or delete items as they land.

## Done

- **Data:** DynamoDB `AlbumRepository` (single table, transactions, TTL) with a contract test suite run against memory and LocalStack. Photo status, dimensions, `takenAt`, derivative keys; conditional status updates.
- **Uploads (phase 1):** presigned PUT intent, complete, stale-intent clean-up, versioned edits (`v{n+1}`), deletes remove all originals and derivatives (also for cancelled uploads). The worker saves only if the photo still has the same original (`replacePhotoIfCurrent`). Local stand-in is an HMAC-signed API route.
- **Media URLs (phase 1):** `urls: {thumbnail, display, original}` signed by CloudFront, S3 presigned GET, or API routes, plus `urls.download` (Content-Disposition where possible; the frontend saves CloudFront files from a blob when cross-origin); 302 redirects in S3 mode; share-link expiry caps.
- **Processing worker (phase 3 code):** magic bytes, EXIF, WebP thumbnail and display sizes, in-process locally, `S3EventWorkerHandler` on AWS.
- **Lambda:** `lambda.StreamLambdaHandler`, `lambda` profile, repo-root `Dockerfile` (one image for API and worker).
- **Frontend:** redesign, themes, editor, zoom, spinner; presigned uploads with progress, cancel and retry; processing tiles and polling; thumbnail/display/original switching; `x-amz-content-sha256` header.
- **Terraform** (`infrastructure/`, validated, not applied): table, buckets with CORS and lifecycle, ECR, API Lambda, CloudFront with signed `/media/*` (generated key pair), processing queue + DLQ + alarm + SNS alerts, S3 → SQS notification, worker Lambda, budget.
- Properties live under `spring.application.*`; env var names are `PHOTO_ALBUM_*`.

## Open follow-ups from wave 1

- Orphaned originals: an object PUT whose intent expired by TTL stays in `originals/`. Needs a scheduled sweeper (EventBridge Scheduler) that deletes objects older than 1 h with no photo item.
- Editor "Replace original" still uploads multipart through the API (6 MB Lambda limit). Move it to a presigned "new version" endpoint (phase 4).
- Worker: mark `FAILED` on the last SQS attempt (`ApproximateReceiveCount`) so photos don't stay `PROCESSING` when messages land in the DLQ.
- Verify libwebp loads after a SnapStart restore (JPEG fallback otherwise).
- Video poster frames: ffmpeg Lambda layer behind `PosterExtractor`.
- `crypto.subtle` (needed for the OAC hash header) exists only on https or localhost.
- Move the CloudFront signing private key from Lambda env/Terraform state to Secrets Manager.
- Budget/alert email is the placeholder `alerts@example.com` in `environments/dev/main.tf`.

## First deployment (phase 2)

1. Replace the alert email. Create the ECR repository with a targeted apply.
2. Build and push the image (`docker buildx build --platform linux/arm64 --provenance=false ...`).
3. Apply everything; confirm the SNS subscription email.
4. Set `media_cdn_domain` to the `cloudfront_domain_name` output and apply again (not needed with a custom domain).
5. Build the SPA and sync it to the site bucket; invalidate CloudFront.
6. Smoke test: create album, upload, thumbnails appear, share link.

See `infrastructure/README.md` for the commands.

## Next waves

- **Wave 2** (parallel):
  - Cognito sign-in: Spring Security resource server, album ownership (`gsi1pk OWNER#{sub}`), frontend sign-in screens, Terraform user pool + app client.
  - Phase 4 album features: non-destructive edits with recipe and revert (presigned new-version upload), Recently Deleted (30-day TTL, `trash/` lifecycle), multi-select bulk delete/move/download, favourites, album cover.
  - Phase 5 AI service in `ai-service/` (Python container Lambda behind SQS, job records in DynamoDB): background removal, object eraser.
- **Wave 3**: phase 6 social features (likes, reactions, comments on shared albums), notifications (SNS, SES), usage analytics in S3 Tables + Athena.
- Known gaps: summary album listing (avoid reading every partition), timeline index on `takenAt`.
