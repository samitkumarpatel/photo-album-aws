package net.samitkumar.photo_album_aws;
import net.samitkumar.photo_album_aws.controller.AlbumController;
import net.samitkumar.photo_album_aws.repository.InMemoryAlbumRepository;
import net.samitkumar.photo_album_aws.repository.AlbumRepository;

import net.samitkumar.photo_album_aws.controller.AlbumController.CreateUpload;
import net.samitkumar.photo_album_aws.controller.AlbumController.Photo;
import net.samitkumar.photo_album_aws.controller.AlbumController.PhotoStatus;
import net.samitkumar.photo_album_aws.media.ApiMediaUrlSigner;
import net.samitkumar.photo_album_aws.media.MediaProperties;
import net.samitkumar.photo_album_aws.media.MediaSize;
import net.samitkumar.photo_album_aws.media.MediaUrlSigner;
import net.samitkumar.photo_album_aws.controller.LocalUploadController;
import net.samitkumar.photo_album_aws.upload.LocalUploadUrlSigner;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AlbumControllerTests {
    private final InMemoryMediaStorage storage = new InMemoryMediaStorage();
    private final InMemoryAlbumRepository repository = new InMemoryAlbumRepository();
    private final List<UUID> processed = new ArrayList<>();
    private final MediaProperties media = new MediaProperties(Duration.ofHours(6), Duration.ofMinutes(15));
    private final LocalUploadUrlSigner uploadSigner = new LocalUploadUrlSigner(media);
    private final AlbumController controller = controller(new ApiMediaUrlSigner());
    private final LocalUploadController uploads = new LocalUploadController(repository, storage, uploadSigner);

    private AlbumController controller(MediaUrlSigner urlSigner) {
        return new AlbumController(repository, storage, uploadSigner, urlSigner, (albumId, photoId) -> processed.add(photoId));
    }

    @Test
    void selectedPhotosAndVideosAreIndependentPrivateCopies() throws IOException {
        var source = controller.createAlbum(new AlbumController.CreateAlbum("Source", null));
        var image = controller.uploadPhoto(source.id(), new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1, 2}));
        var video = controller.uploadPhoto(source.id(), new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[]{3, 4}));
        repository.replacePhoto(source.id(), stored(source.id(), image.id()).withStatus(PhotoStatus.READY));
        repository.replacePhoto(source.id(), stored(source.id(), video.id()).withStatus(PhotoStatus.READY));
        controller.createShare(source.id(), new AlbumController.CreateShare(1, AlbumController.DurationUnit.DAYS));
        var selectedImage = new AlbumController.SelectedMedia(source.id(), image.id());
        var copied = controller.createFromSelection(new AlbumController.CreateSelectedAlbum(" Favorites ", "Mixed media",
                List.of(selectedImage, new AlbumController.SelectedMedia(source.id(), video.id()), selectedImage)));
        assertEquals("Favorites", copied.name());
        assertEquals(2, copied.photos().size());
        assertTrue(copied.shares().isEmpty());
        assertTrue(copied.photos().stream().noneMatch(p -> p.id().equals(image.id()) || p.id().equals(video.id())));
        controller.deleteAlbum(source.id());
        for (var photo : copied.photos()) {
            var bytes = storage.open(stored(copied.id(), photo.id()).objectKey()).readAllBytes();
            assertArrayEquals(photo.contentType().startsWith("video/") ? new byte[]{3, 4} : new byte[]{1, 2}, bytes);
            assertTrue(processed.contains(photo.id()));
        }
    }

    @Test
    void selectionRejectsUnfinishedMediaAndRollsBackFailedCopies() throws IOException {
        var source = controller.createAlbum(new AlbumController.CreateAlbum("Source", null));
        var photo = controller.uploadPhoto(source.id(), new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1}));
        var request = new AlbumController.CreateSelectedAlbum("New", null, List.of(new AlbumController.SelectedMedia(source.id(), photo.id())));
        assertStatus(HttpStatus.CONFLICT, () -> controller.createFromSelection(request));
        repository.replacePhoto(source.id(), stored(source.id(), photo.id()).withStatus(PhotoStatus.READY));
        storage.delete(stored(source.id(), photo.id()).objectKey());
        assertThrows(IllegalStateException.class, () -> controller.createFromSelection(request));
        assertEquals(List.of(source.id()), controller.listAlbums().stream().map(AlbumController.AlbumResponse::id).toList());
    }

    @Test
    void ownersSeeActiveSharesAndPublicViewsDoNotExposeTokens() {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var share = controller.createShare(album.id(), new AlbumController.CreateShare(1, AlbumController.DurationUnit.DAYS));
        assertEquals(List.of(share), controller.getAlbum(album.id()).shares());
        assertNull(controller.sharedAlbum(share.token()).shares());
        assertEquals(share.expiresAt(), controller.sharedStatus(share.token()));
        controller.revokeShare(album.id(), share.token());
        assertTrue(controller.getAlbum(album.id()).shares().isEmpty());
        assertStatus(HttpStatus.NOT_FOUND, () -> controller.sharedStatus(share.token()));
    }

    @Test
    void renamesAlbumAndKeepsMedia() throws IOException {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", "old"));
        controller.uploadPhoto(album.id(), new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1, 2}));

        var renamed = controller.updateAlbum(album.id(), new AlbumController.UpdateAlbum("  Lisbon  ", null));

        assertEquals("Lisbon", renamed.name());
        assertEquals("old", renamed.description());
        assertEquals(1, controller.getAlbum(album.id()).photos().size());
        assertEquals(album.createdAt(), renamed.createdAt());
    }

    @Test
    void rejectsBlankName() {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> controller.updateAlbum(album.id(), new AlbumController.UpdateAlbum("  ", null)));
    }

    @Test
    void deletesAlbumMediaAndShares() throws IOException {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var photo = controller.uploadPhoto(album.id(), new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1}));
        controller.replacePhoto(album.id(), photo.id(), new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{2}));
        storage.putObject("derived/" + album.id() + "/" + photo.id() + "/thumb.webp", "image/webp", 1, new ByteArrayInputStream(new byte[]{3}));
        var share = controller.createShare(album.id(), new AlbumController.CreateShare(1, AlbumController.DurationUnit.DAYS));

        controller.deleteAlbum(album.id());

        assertTrue(controller.listAlbums().isEmpty());
        assertTrue(storage.size("originals/" + album.id() + "/" + photo.id() + "/v1.jpg").isEmpty());
        assertTrue(storage.size("originals/" + album.id() + "/" + photo.id() + "/v2.jpg").isEmpty());
        assertTrue(storage.size("derived/" + album.id() + "/" + photo.id() + "/thumb.webp").isEmpty());
        assertStatus(HttpStatus.NOT_FOUND, () -> controller.sharedAlbum(share.token()));
        assertStatus(HttpStatus.NOT_FOUND, () -> controller.deleteAlbum(album.id()));
    }

    @Test
    void multipartUploadStoresAVersionedOriginalAndStartsProcessing() throws IOException {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var photo = controller.uploadPhoto(album.id(), new MockMultipartFile("file", "IMG.JPG", "image/jpeg", new byte[]{1}));

        assertEquals(PhotoStatus.PROCESSING, photo.status());
        assertEquals("originals/" + album.id() + "/" + photo.id() + "/v1.jpg", stored(album.id(), photo.id()).objectKey());
        assertEquals(List.of(photo.id()), processed);
    }

    @Test
    void replacingWritesTheNextVersionKeepsOldOnesAndReprocesses() throws IOException {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var photo = controller.uploadPhoto(album.id(), new MockMultipartFile("file", "a.png", "image/png", new byte[]{1}));
        repository.replacePhoto(album.id(), stored(album.id(), photo.id()).withStatus(PhotoStatus.READY).withMetadata(4, 3, null).withDerivatives("t", "d"));

        var edited = controller.replacePhoto(album.id(), photo.id(), new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{7, 8, 9}));

        assertEquals(photo.id(), edited.id());
        assertEquals(photo.uploadedAt(), edited.uploadedAt());
        assertNotNull(edited.editedAt());
        assertEquals("image/jpeg", edited.contentType());
        assertEquals(3, edited.size());
        assertEquals(PhotoStatus.PROCESSING, edited.status());
        var record = stored(album.id(), photo.id());
        assertEquals("originals/" + album.id() + "/" + photo.id() + "/v2.jpg", record.objectKey());
        // Kept until processing replaces them, so the grid keeps a thumbnail.
        assertEquals("t", record.thumbnailKey());
        assertEquals("d", record.displayKey());
        assertNull(record.width());
        assertArrayEquals(new byte[]{7, 8, 9}, storage.open(record.objectKey()).readAllBytes());
        assertArrayEquals(new byte[]{1}, storage.open("originals/" + album.id() + "/" + photo.id() + "/v1.png").readAllBytes());
        assertEquals(List.of(photo.id(), photo.id()), processed);
        assertEquals(1, controller.listPhotos(album.id()).size());
    }

    @Test
    void refusesToReplaceVideos() throws IOException {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var video = controller.uploadPhoto(album.id(), new MockMultipartFile("file", "v.mp4", "video/mp4", new byte[]{1}));
        assertStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE, () -> controller.replacePhoto(album.id(), video.id(), new MockMultipartFile("file", "v.jpg", "image/jpeg", new byte[]{1})));
    }

    @Test
    void deletingAPhotoRemovesEveryVersionAndDerivative() throws IOException {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var keep = controller.uploadPhoto(album.id(), new MockMultipartFile("file", "keep.png", "image/png", new byte[]{1}));
        var photo = controller.uploadPhoto(album.id(), new MockMultipartFile("file", "a.png", "image/png", new byte[]{1}));
        controller.replacePhoto(album.id(), photo.id(), new MockMultipartFile("file", "a.png", "image/png", new byte[]{2}));
        storage.putObject("derived/" + album.id() + "/" + photo.id() + "/display.webp", "image/webp", 1, new ByteArrayInputStream(new byte[]{3}));

        controller.deletePhoto(album.id(), photo.id());

        assertTrue(storage.size("originals/" + album.id() + "/" + photo.id() + "/v1.png").isEmpty());
        assertTrue(storage.size("originals/" + album.id() + "/" + photo.id() + "/v2.png").isEmpty());
        assertTrue(storage.size("derived/" + album.id() + "/" + photo.id() + "/display.webp").isEmpty());
        assertTrue(storage.size(stored(album.id(), keep.id()).objectKey()).isPresent());
    }

    /* ---------- upload intents ---------- */

    @Test
    void uploadIntentValidatesSizeTypeAndAlbum() {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        assertStatus(HttpStatus.BAD_REQUEST, () -> controller.createUpload(album.id(), new CreateUpload("a.jpg", "image/jpeg", 0L)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> controller.createUpload(album.id(), new CreateUpload("a.jpg", "image/jpeg", null)));
        assertStatus(HttpStatus.CONTENT_TOO_LARGE, () -> controller.createUpload(album.id(), new CreateUpload("a.mp4", "video/mp4", AlbumController.MAX_UPLOAD_BYTES + 1)));
        assertStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE, () -> controller.createUpload(album.id(), new CreateUpload("a.txt", "text/plain", 5L)));
        assertStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE, () -> controller.createUpload(album.id(), new CreateUpload("a.svg", "image/svg+xml", 5L)));
        assertStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE, () -> controller.createUpload(album.id(), new CreateUpload("a", "image/*", 5L)));
        assertStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE, () -> controller.createUpload(album.id(), new CreateUpload("a", null, 5L)));
        assertStatus(HttpStatus.NOT_FOUND, () -> controller.createUpload(UUID.randomUUID(), new CreateUpload("a.jpg", "image/jpeg", 5L)));
        assertTrue(controller.getAlbum(album.id()).photos().isEmpty());
        assertDoesNotThrow(() -> controller.createUpload(album.id(), new CreateUpload("a.mp4", "video/mp4", AlbumController.MAX_UPLOAD_BYTES)));
    }

    @Test
    void uploadIntentRecordsAnUploadingPhotoAndALockedUrl() {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));

        var intent = controller.createUpload(album.id(), new CreateUpload("C:\\fakepath\\holiday", "image/JPEG; name=x", 3L));

        assertEquals("PUT", intent.method());
        assertEquals("image/jpeg", intent.headers().get("Content-Type"));
        assertTrue(intent.uploadUrl().startsWith("/api/uploads/" + album.id() + "/" + intent.photoId() + "?expires="));
        assertTrue(intent.expiresAt().isAfter(Instant.now().plusSeconds(14 * 60)));
        var record = stored(album.id(), intent.photoId());
        assertEquals(PhotoStatus.UPLOADING, record.status());
        assertEquals("holiday", record.filename());
        assertEquals("originals/" + album.id() + "/" + intent.photoId() + "/v1.jpg", record.objectKey());
        var listed = controller.getAlbum(album.id()).photos().getFirst();
        assertEquals(PhotoStatus.UPLOADING, listed.status());
        assertNull(listed.urls());
        assertStatus(HttpStatus.NOT_FOUND, () -> controller.getPhoto(album.id(), intent.photoId(), null, false));
    }

    @Test
    void uploadThenCompleteStartsProcessingOnce() throws IOException {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var intent = controller.createUpload(album.id(), new CreateUpload("a.png", "image/png", 3L));
        assertStatus(HttpStatus.CONFLICT, () -> controller.completeUpload(album.id(), intent.photoId()));

        put(intent, "image/png", new byte[]{4, 5, 6});
        var completed = controller.completeUpload(album.id(), intent.photoId());

        assertEquals(PhotoStatus.PROCESSING, completed.status());
        assertNotNull(completed.urls());
        assertEquals(List.of(intent.photoId()), processed);
        assertEquals(PhotoStatus.PROCESSING, controller.completeUpload(album.id(), intent.photoId()).status());
        assertEquals(1, processed.size());
        assertArrayEquals(new byte[]{4, 5, 6}, body(controller.getPhoto(album.id(), intent.photoId(), "original", false)));
        assertStatus(HttpStatus.CONFLICT, () -> put(intent, "image/png", new byte[]{4, 5, 6}));
        assertStatus(HttpStatus.NOT_FOUND, () -> controller.completeUpload(album.id(), UUID.randomUUID()));
    }

    @Test
    void localUploadUrlEnforcesSignatureTypeAndLength() {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var intent = controller.createUpload(album.id(), new CreateUpload("a.png", "image/png", 3L));

        assertStatus(HttpStatus.BAD_REQUEST, () -> put(intent, "image/jpeg", new byte[]{1, 2, 3}));
        assertStatus(HttpStatus.BAD_REQUEST, () -> put(intent, "image/png", new byte[]{1, 2}));
        var query = UriComponentsBuilder.fromUriString(intent.uploadUrl()).build().getQueryParams();
        long expires = Long.parseLong(query.getFirst("expires"));
        assertStatus(HttpStatus.FORBIDDEN, () -> uploads.upload(album.id(), intent.photoId(), expires, "forged", null, null, null, request("image/png", new byte[]{1, 2, 3})));
        assertStatus(HttpStatus.FORBIDDEN, () -> uploads.upload(album.id(), intent.photoId(), expires + 60, query.getFirst("signature"), null, null, null, request("image/png", new byte[]{1, 2, 3})));
        assertTrue(storage.size(stored(album.id(), intent.photoId()).objectKey()).isEmpty());
        assertTrue(uploadSigner.verify(album.id(), intent.photoId(), stored(album.id(), intent.photoId()).objectKey(), "image/png", 3, expires, query.getFirst("signature")));
        assertFalse(uploadSigner.verify(album.id(), intent.photoId(), stored(album.id(), intent.photoId()).objectKey(), "image/png", 3, Instant.now().minusSeconds(1).getEpochSecond(), query.getFirst("signature")));
    }

    @Test
    void localReplacementUploadStagesThenPromotesAnEditedVersion() throws IOException {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var photo = controller.uploadPhoto(album.id(), new MockMultipartFile("file", "a.png", "image/png", new byte[]{1}));
        repository.replacePhoto(album.id(), stored(album.id(), photo.id()).withStatus(PhotoStatus.READY));
        var request = new CreateUpload("edited.jpg", "image/jpeg", 3L);
        var intent = controller.createReplacementUpload(album.id(), photo.id(), request);

        assertStatus(HttpStatus.BAD_REQUEST, () -> put(intent.uploadUrl(), "image/png", new byte[]{4, 5, 6}));
        assertStatus(HttpStatus.BAD_REQUEST, () -> put(intent.uploadUrl(), "image/jpeg", new byte[]{4, 5}));
        var forgedUrl = intent.uploadUrl().replace("size=3", "size=2");
        assertStatus(HttpStatus.FORBIDDEN, () -> put(forgedUrl, "image/jpeg", new byte[]{4, 5}));
        put(intent.uploadUrl(), "image/jpeg", new byte[]{4, 5, 6});
        var encodedKey = UriComponentsBuilder.fromUriString(intent.uploadUrl()).build().getQueryParams().getFirst("objectKey");
        var stagedKey = java.net.URLDecoder.decode(encodedKey, java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(new byte[]{4, 5, 6}, storage.open(stagedKey).readAllBytes());
        assertArrayEquals(new byte[]{1}, storage.open(stored(album.id(), photo.id()).objectKey()).readAllBytes());
        assertEquals(1, PhotoHistory.version(stored(album.id(), photo.id())));
        assertEquals(List.of(photo.id()), processed);

        var completed = controller.completeReplacementUpload(album.id(), photo.id(), intent.uploadId(), intent.version(), request);
        assertEquals(2, completed.version());
        assertEquals(PhotoStatus.PROCESSING, completed.status());
        assertArrayEquals(new byte[]{4, 5, 6}, storage.open(stored(album.id(), photo.id()).objectKey()).readAllBytes());
        assertArrayEquals(new byte[]{1}, storage.open("originals/" + album.id() + "/" + photo.id() + "/v1.png").readAllBytes());
        assertTrue(storage.size(stagedKey).isEmpty());
        assertEquals(List.of(photo.id(), photo.id()), processed);
        assertStatus(HttpStatus.CONFLICT, () -> controller.completeReplacementUpload(album.id(), photo.id(), intent.uploadId(), intent.version(), request));
    }

    @Test
    void completeRejectsAStoredObjectOfTheWrongSize() throws IOException {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var intent = controller.createUpload(album.id(), new CreateUpload("a.png", "image/png", 3L));
        storage.putObject(stored(album.id(), intent.photoId()).objectKey(), "image/png", 2, new ByteArrayInputStream(new byte[]{1, 2}));
        assertStatus(HttpStatus.CONFLICT, () -> controller.completeUpload(album.id(), intent.photoId()));
        assertTrue(processed.isEmpty());
    }

    @Test
    void completingAPhotoTheWorkerAlreadyFinishedIsANoOp() {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var intent = controller.createUpload(album.id(), new CreateUpload("a.png", "image/png", 3L));
        repository.replacePhoto(album.id(), stored(album.id(), intent.photoId()).withStatus(PhotoStatus.READY));

        assertEquals(PhotoStatus.READY, controller.completeUpload(album.id(), intent.photoId()).status());
        assertTrue(processed.isEmpty());
    }

    @Test
    void abandonedUploadsAreHiddenAndRemoved() {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        UUID id = UUID.randomUUID();
        repository.addPhoto(album.id(), new Photo(id, "old.jpg", "image/jpeg", 3, "originals/" + album.id() + "/" + id + "/v1.jpg",
                Instant.now().minus(Photo.UPLOAD_WINDOW).minusSeconds(1), null, PhotoStatus.UPLOADING, null, null, null, null, null));
        var fresh = controller.createUpload(album.id(), new CreateUpload("new.jpg", "image/jpeg", 3L));

        assertEquals(List.of(fresh.photoId()), controller.listAlbums().getFirst().photos().stream().map(AlbumController.PhotoResponse::id).toList());
        assertEquals(List.of(fresh.photoId()), repository.findById(album.id()).orElseThrow().photos().stream().map(Photo::id).toList());
    }

    @Test
    void uploadingPhotosCanBeDeletedWithOrWithoutTheirObject() throws IOException {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var cancelled = controller.createUpload(album.id(), new CreateUpload("a.png", "image/png", 3L));
        var interrupted = controller.createUpload(album.id(), new CreateUpload("b.png", "image/png", 3L));
        put(interrupted, "image/png", new byte[]{1, 2, 3});
        String interruptedKey = stored(album.id(), interrupted.photoId()).objectKey();

        controller.deletePhoto(album.id(), cancelled.photoId());
        controller.deletePhoto(album.id(), interrupted.photoId());

        assertTrue(controller.getAlbum(album.id()).photos().isEmpty());
        assertTrue(storage.size(interruptedKey).isEmpty());
        assertStatus(HttpStatus.NOT_FOUND, () -> controller.completeUpload(album.id(), cancelled.photoId()));
    }

    /* ---------- media URLs ---------- */

    @Test
    void apiUrlsPointAtThePhotoRoutesAndChangeWhenThePhotoChanges() throws IOException {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var photo = controller.uploadPhoto(album.id(), new MockMultipartFile("file", "a.png", "image/png", new byte[]{1}));
        String base = "/api/albums/" + album.id() + "/photos/" + photo.id();

        var urls = controller.getAlbum(album.id()).photos().getFirst().urls();
        assertTrue(urls.thumbnail().startsWith(base + "?size=thumbnail&v="), urls.thumbnail());
        assertTrue(urls.display().startsWith(base + "?size=display&v="));
        assertTrue(urls.original().startsWith(base + "?size=original&v="));
        assertEquals(urls.original() + "&download=1", urls.download());

        var edited = controller.replacePhoto(album.id(), photo.id(), new MockMultipartFile("file", "a.png", "image/png", new byte[]{2}));
        assertNotEquals(urls.original(), edited.urls().original());

        var share = controller.createShare(album.id(), new AlbumController.CreateShare(1, AlbumController.DurationUnit.DAYS));
        var shared = controller.sharedAlbum(share.token()).photos().getFirst().urls();
        assertTrue(shared.thumbnail().startsWith("/api/shared/" + share.token() + "/photos/" + photo.id() + "?size=thumbnail&v="));
    }

    @Test
    void streamsDerivativesAndFallsBackToTheOriginal() throws IOException {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var photo = controller.uploadPhoto(album.id(), new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 1}));
        String thumb = storage.putObject("derived/" + album.id() + "/" + photo.id() + "/thumb.webp", "image/webp", 1, new ByteArrayInputStream(new byte[]{9}));
        repository.replacePhoto(album.id(), stored(album.id(), photo.id()).withStatus(PhotoStatus.READY)
                .withDerivatives(thumb, "derived/" + album.id() + "/" + photo.id() + "/missing.webp"));

        var thumbnail = controller.getPhoto(album.id(), photo.id(), "thumbnail", false);
        assertEquals("image/webp", thumbnail.getHeaders().getContentType().toString());
        assertArrayEquals(new byte[]{9}, body(thumbnail));
        var display = controller.getPhoto(album.id(), photo.id(), "display", false);
        assertEquals("image/png", display.getHeaders().getContentType().toString());
        assertArrayEquals(new byte[]{1, 1}, body(display));
        assertStatus(HttpStatus.BAD_REQUEST, () -> controller.getPhoto(album.id(), photo.id(), "huge", false));

        // A download is always the original, saved under the photo's filename.
        var download = controller.getPhoto(album.id(), photo.id(), "thumbnail", true);
        assertArrayEquals(new byte[]{1, 1}, body(download));
        assertEquals("a.png", download.getHeaders().getContentDisposition().getFilename());
        assertTrue(download.getHeaders().getContentDisposition().isAttachment());
        assertNull(display.getHeaders().getFirst("Content-Disposition"));
    }

    @Test
    void sharedAlbumsHidePendingAndFailedPhotos() throws IOException {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var ready = controller.uploadPhoto(album.id(), new MockMultipartFile("file", "a.png", "image/png", new byte[]{1}));
        var pending = controller.createUpload(album.id(), new CreateUpload("b.png", "image/png", 3L));
        var failed = controller.uploadPhoto(album.id(), new MockMultipartFile("file", "c.png", "image/png", new byte[]{1}));
        repository.updatePhotoStatus(album.id(), failed.id(), PhotoStatus.PROCESSING, PhotoStatus.FAILED);
        var share = controller.createShare(album.id(), new AlbumController.CreateShare(1, AlbumController.DurationUnit.DAYS));

        assertEquals(List.of(ready.id()), controller.sharedAlbum(share.token()).photos().stream().map(AlbumController.PhotoResponse::id).toList());
        assertStatus(HttpStatus.NOT_FOUND, () -> controller.getSharedPhoto(share.token(), pending.photoId(), null, false));
        assertStatus(HttpStatus.NOT_FOUND, () -> controller.getSharedPhoto(share.token(), failed.id(), null, false));
        assertArrayEquals(new byte[]{1}, body(controller.getSharedPhoto(share.token(), ready.id(), null, false)));
    }

    @Test
    void listSharesReturnsActiveLinksAndRevokeStopsThemWorking() {
        var album = controller.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var other = controller.createAlbum(new AlbumController.CreateAlbum("Other", null));
        var dayShare = controller.createShare(album.id(), new AlbumController.CreateShare(1, AlbumController.DurationUnit.DAYS));
        var weekShare = controller.createShare(album.id(), new AlbumController.CreateShare(1, AlbumController.DurationUnit.WEEKS));
        controller.createShare(other.id(), new AlbumController.CreateShare(1, AlbumController.DurationUnit.DAYS));

        // Soonest-expiring first, and only this album's links.
        assertEquals(List.of(dayShare.token(), weekShare.token()),
                controller.listShares(album.id()).stream().map(AlbumController.ShareResponse::token).toList());

        controller.revokeShare(album.id(), dayShare.token());

        assertEquals(List.of(weekShare.token()), controller.listShares(album.id()).stream().map(AlbumController.ShareResponse::token).toList());
        assertStatus(HttpStatus.NOT_FOUND, () -> controller.sharedAlbum(dayShare.token()));
        // A token cannot be revoked through another album's path.
        assertStatus(HttpStatus.NOT_FOUND, () -> controller.revokeShare(other.id(), weekShare.token()));
        assertEquals(List.of(weekShare.token()), controller.listShares(album.id()).stream().map(AlbumController.ShareResponse::token).toList());
    }

    @Test
    void externalUrlsRedirectAndSharedOnesExpireWithTheLink() throws IOException {
        var notAfter = new AtomicReference<Instant>();
        var redirecting = controller((photo, size, key, apiPath, limit) -> {
            notAfter.set(limit);
            return "https://cdn.example/" + key + "?size=" + size.param();
        });
        var album = redirecting.createAlbum(new AlbumController.CreateAlbum("Trip", null));
        var photo = redirecting.uploadPhoto(album.id(), new MockMultipartFile("file", "a.png", "image/png", new byte[]{1}));
        String key = stored(album.id(), photo.id()).objectKey();

        var owner = redirecting.getPhoto(album.id(), photo.id(), "thumbnail", false);
        assertEquals(HttpStatus.FOUND, owner.getStatusCode());
        assertEquals("https://cdn.example/" + key + "?size=thumbnail", owner.getHeaders().getLocation().toString());
        assertNull(notAfter.get());

        var share = redirecting.createShare(album.id(), new AlbumController.CreateShare(2, AlbumController.DurationUnit.HOURS));
        assertEquals("https://cdn.example/" + key + "?size=original", redirecting.sharedAlbum(share.token()).photos().getFirst().urls().original());
        assertTrue(notAfter.get().isBefore(share.expiresAt()));
        assertTrue(notAfter.get().isBefore(Instant.now().plus(Duration.ofMinutes(5))));
        assertEquals(HttpStatus.FOUND, redirecting.getSharedPhoto(share.token(), photo.id(), null, false).getStatusCode());
        assertTrue(notAfter.get().isBefore(Instant.now().plus(Duration.ofMinutes(5))));
        Instant soon = Instant.now().plusSeconds(10);
        repository.saveShare("soon", new AlbumController.Share(album.id(), soon));
        redirecting.sharedAlbum("soon");
        assertEquals(soon, notAfter.get());
    }

    @Test
    void derivativeKeysFallBackToLargerRenditions() {
        var photo = new Photo(UUID.randomUUID(), "a", "image/png", 1, "o", Instant.now(), null);
        assertEquals("o", MediaSize.THUMBNAIL.key(photo));
        assertEquals("d", MediaSize.THUMBNAIL.key(photo.withDerivatives(null, "d")));
        assertEquals("t", MediaSize.THUMBNAIL.key(photo.withDerivatives("t", "d")));
        assertEquals("o", MediaSize.ORIGINAL.key(photo.withDerivatives("t", "d")));
        assertEquals(3, AlbumController.nextVersion("p/originals/a/b/v2.jpg"));
        assertEquals(2, AlbumController.nextVersion("legacyAlbum/legacyPhoto"));
        assertEquals("mov", AlbumController.extension("clip", "video/quicktime"));
        assertEquals("heic", AlbumController.extension("IMG_1.HEIC", "image/heic"));
    }

    /* ---------- helpers ---------- */

    private void put(AlbumController.UploadIntent intent, String contentType, byte[] bytes) throws IOException {
        put(intent.uploadUrl(), contentType, bytes);
    }

    private void put(String uploadUrl, String contentType, byte[] bytes) throws IOException {
        var uri = UriComponentsBuilder.fromUriString(uploadUrl).build();
        var segments = uri.getPathSegments();
        var query = uri.getQueryParams();
        String key = query.getFirst("objectKey");
        String type = query.getFirst("contentType");
        String size = query.getFirst("size");
        uploads.upload(UUID.fromString(segments.get(2)), UUID.fromString(segments.get(3)),
                Long.parseLong(query.getFirst("expires")), query.getFirst("signature"),
                key == null ? null : java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8),
                type == null ? null : java.net.URLDecoder.decode(type, java.nio.charset.StandardCharsets.UTF_8),
                size == null ? null : Long.valueOf(size), request(contentType, bytes));
    }

    private static MockHttpServletRequest request(String contentType, byte[] bytes) {
        var request = new MockHttpServletRequest("PUT", "/api/uploads");
        request.setContentType(contentType);
        request.setContent(bytes);
        return request;
    }

    private Photo stored(UUID albumId, UUID photoId) {
        return repository.findById(albumId).orElseThrow().photos().stream().filter(p -> p.id().equals(photoId)).findFirst().orElseThrow();
    }

    private static byte[] body(ResponseEntity<StreamingResponseBody> response) throws IOException {
        var out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        return out.toByteArray();
    }

    private static void assertStatus(HttpStatus expected, org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, call).getStatusCode());
    }
}
