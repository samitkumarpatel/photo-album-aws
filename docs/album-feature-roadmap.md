# Album feature roadmap

Requested 2026-10-11. Implement features in this order, preserving existing uploads, image/video viewing, editing, recipes, version history, collages, presentation settings, sharing and mobile layouts. Each feature requires backend/frontend checks and regression checks before it is marked complete. Deployment is tracked separately from local completion.

## This implementation round

1. **Favorites — implemented, verified locally**
   - Persist a heart independently of the album-cover star, for photos and videos.
   - Show favorites in owner galleries and the viewer; allow filtering in Photos and individual albums.
   - Preserve marks through replacement/version restoration. Shared links stay view-only and do not expose owner favorites.
2. **ZIP downloads — implemented, verified locally**
   - Download a complete album or a selection of ready originals as one archive.
   - Preserve filenames, disambiguate duplicates, show progress, support cancellation and report failures without producing incomplete archives.
   - Keep individual downloads working; avoid routing archive bytes through Lambda/API Gateway.
3. **Recently Deleted — implemented, verified locally**
   - Add recoverable deletion of photos and albums for 30 days, with restore and explicit permanent deletion.
   - Show retained photo thumbnails and deleted album cover previews; gracefully fall back to an icon if no preview is available. Preview access expires with recovery and does not restore deleted items.
   - Preserve originals, derivatives, edit recipes/history, favorites, ordering and album presentation on restore.
   - Revoke album share links on deletion; restoring must not reactivate old links.
   - Keep existing permanent-delete endpoints working for upload/copy cleanup. Expired records must not be restorable; media cleanup must precede removing their recovery records.
4. **Bulk organization — implemented, verified locally**
   - Move or copy selected photos/videos into an existing album, and move selections to Recently Deleted.
   - Preserve all recorded versions and recipes; retain independent storage for copies.
   - Show per-item progress/results, cancellation between items, and allow retry of failed items. A failed transfer must leave the source intact.
5. **Custom ordering — implemented, verified locally**
   - Persist a manual photo/video order per album, editable through drag/drop and accessible move buttons.
   - Add capture-date and filename sorting alongside current upload-date sorting.
   - Use manual order consistently in owner/shared galleries and slideshows when selected. Newly added photos remain visible; stale or duplicate IDs cannot hide media.

## Following rounds

6. **Photo titles, descriptions and tags:** editable searchable metadata, separate from editor captions burned into exported pixels.
7. **Duplicate detection:** identify identical uploads and offer cleanup without automatically deleting originals.
8. **Shared contributions and comments:** explicit contributor permissions, moderation, comments and reactions. Depends on account identity/ownership decisions.
9. **Editing comparison and presets:** before/after split view and reusable personal presets; preserve current hold-to-compare behavior.
10. **Video editing:** trimming, poster selection, audio muting and export while retaining the source.
11. **Advanced AI tools:** object removal, face grouping and subject search. Select models, execution location and costs before implementation.
    - See [AI features plan](ai-features-plan.md) for the proposed model evaluation, editor workflow, AWS integration and ordered future tasks. Planning only; implementation remains deferred.

## Separately planned foundations

Account sign-in, ownership, recipient-restricted/password-protected sharing and billing remain later work as previously scheduled. Until identity exists, owner preferences use the application's existing single-owner model.

## Verification and delivery

- Existing API routes and payload fields remain compatible; new metadata defaults safely for older albums.
- Test owner and public-share paths, mixed photo/video albums, processing/failed uploads, empty galleries and mobile interaction.
- Test recoverability and rollback/failure paths, including preservation of edits and revoked-share behavior.
- Avoid live photo changes during verification. Do not deploy an unfinished feature batch.
- Record completed checks and remaining limitations here after implementation.

## Current delivery status

The first five features are implemented locally. Nothing in this batch has been deployed. No new Terraform resources are required; data uses the existing DynamoDB table and media bucket. Deploy the updated API and processing worker before deploying the frontend, since both backend roles must understand recovery states.

- Full backend suite passed: 113 tests, including LocalStack DynamoDB/S3 integration checks and regressions for S3 event timing, concurrent edits/moves, deletion locks and edit rollback during deletion.
- All 12 frontend tests passed, covering loading, sorting, ZIP bytes/filenames, HTTP errors, cancellation and archive-size bounds. The production frontend build passed.
- Desktop and mobile Chromium checks cover favorites, viewing/edit rendering, manual/shared ordering, ZIP contents, bulk copy/move/delete, album/photo recovery and revoked shares, using isolated local fixtures.
- A mixed photo/video mobile check also passed for video filtering, favorites, playback, original-byte ZIP contents and bulk copying.
- The built frontend was also checked through Spring: direct navigation and page refresh work on Recently Deleted, Sharing, Photos and Albums without JavaScript errors. Isolated test backends were stopped after verification.
- ZIP downloads are limited to 512 MB of original bytes to bound browser memory use; larger albums can be downloaded in selections. Originals are streamed sequentially and saved only after the archive completes.
- Recovery is limited to 30 days. Expired media is permanently cleaned up when Recently Deleted is accessed; a scheduled cleanup job remains a follow-up for unattended storage reclamation. Recovery records remain until deletion succeeds, and a permanent-deletion lock prevents concurrent restoration of partially purged media.
- Existing permanent-delete endpoints remain available for upload/copy cleanup. Normal UI deletion uses the new recoverable endpoints; unfinished uploads still use cancellation/permanent cleanup.

Next implementation task: **6 — Photo titles, descriptions and tags**.
