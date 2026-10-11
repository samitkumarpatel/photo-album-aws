package net.samitkumar.photo_album_aws;

import net.samitkumar.photo_album_aws.controller.AlbumController;
import net.samitkumar.photo_album_aws.controller.AlbumController.*;
import net.samitkumar.photo_album_aws.controller.AlbumOrganizationController;
import net.samitkumar.photo_album_aws.controller.AlbumOrganizationController.*;
import net.samitkumar.photo_album_aws.controller.CreativeController;
import net.samitkumar.photo_album_aws.media.ApiMediaUrlSigner;
import net.samitkumar.photo_album_aws.media.MediaProperties;
import net.samitkumar.photo_album_aws.repository.InMemoryAlbumRepository;
import net.samitkumar.photo_album_aws.upload.LocalUploadUrlSigner;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AlbumOrganizationTests {
    private final InMemoryAlbumRepository repository = new InMemoryAlbumRepository();
    private final InMemoryMediaStorage storage = new InMemoryMediaStorage();
    private final List<UUID> processed = new ArrayList<>();
    private final AlbumController albums = new AlbumController(repository, storage,
            new LocalUploadUrlSigner(new MediaProperties(Duration.ofHours(6), Duration.ofMinutes(15))), new ApiMediaUrlSigner(), (a, p) -> processed.add(p));
    private final AlbumOrganizationController organization = organization(storage);
    private AlbumOrganizationController organization(MediaStorage media) { return new AlbumOrganizationController(repository, media, albums, (a, p) -> processed.add(p)); }
    private AlbumResponse album(String name) { return albums.createAlbum(new AlbumController.CreateAlbum(name, null)); }
    private PhotoResponse upload(UUID albumId, String name) throws IOException {
        var p = albums.uploadPhoto(albumId, new MockMultipartFile("file", name, name.endsWith(".mp4") ? "video/mp4" : "image/jpeg", new byte[]{1, 2, 3}));
        repository.replacePhoto(albumId, raw(albumId, p.id()).withStatus(PhotoStatus.READY));
        return albums.getAlbum(albumId).photos().stream().filter(photo -> photo.id().equals(p.id())).findFirst().orElseThrow();
    }
    private Photo raw(UUID a, UUID p) { return repository.findPhotoIncludingDeleted(a, p).orElseThrow(); }
    private void status(HttpStatus expected, org.junit.jupiter.api.function.Executable run) { assertEquals(expected, assertThrows(ResponseStatusException.class, run).getStatusCode()); }

    @Test void deletedPreviewsServeRetainedThumbnailsWithoutRestoringItems() throws IOException {
        var a = album("Trip"); var p = upload(a.id(), "a.jpg");
        String thumb = storage.putObject("derived/test/thumb.webp", "image/webp", 2, new java.io.ByteArrayInputStream(new byte[]{7, 8}));
        Photo original = raw(a.id(), p.id());
        repository.replacePhoto(a.id(), new Photo(original.id(), original.filename(), original.contentType(), original.size(), original.objectKey(),
                original.uploadedAt(), original.editedAt(), original.status(), original.width(), original.height(), original.takenAt(), thumb, null));
        var deleted = organization.trashPhoto(a.id(), p.id());
        assertTrue(deleted.previewUrl().startsWith("/api/trash/" + deleted.id() + "/preview?"));
        assertEquals(deleted.previewUrl(), organization.trash().getFirst().previewUrl());
        var response = organization.preview(deleted.id());
        var bytes = new java.io.ByteArrayOutputStream(); response.getBody().writeTo(bytes);
        assertArrayEquals(new byte[]{7, 8}, bytes.toByteArray());
        assertEquals("image/webp", response.getHeaders().getContentType().toString());
        assertTrue(albums.getAlbum(a.id()).photos().isEmpty());
        organization.restore(deleted.id());
        status(HttpStatus.NOT_FOUND, () -> organization.preview(deleted.id()));
        var removedAlbum = organization.trashAlbum(a.id());
        assertNotNull(removedAlbum.previewUrl());
        assertTrue(albums.listAlbums().isEmpty());
        organization.purge(removedAlbum.id());
        status(HttpStatus.NOT_FOUND, () -> organization.preview(removedAlbum.id()));
    }

    @Test void deletedPreviewUsesOriginalFallbackAndRejectsExpiredItems() throws IOException {
        var a = album("Trip"); var p = upload(a.id(), "a.jpg");
        var deleted = organization.trashPhoto(a.id(), p.id());
        var bytes = new java.io.ByteArrayOutputStream(); organization.preview(deleted.id()).getBody().writeTo(bytes);
        assertArrayEquals(new byte[]{1, 2, 3}, bytes.toByteArray());
        var expired = new TrashEntry(deleted.id(), a.id(), p.id(), a.name(), p.filename(), "photo", 1,
                PhotoStatus.READY, Instant.now().minus(Duration.ofDays(31)), Instant.now().minusSeconds(1));
        repository.putTrash(deleted.id().toString(), PhotoHistory.JSON.writeValueAsString(expired));
        status(HttpStatus.GONE, () -> organization.preview(deleted.id()));
        var video = upload(a.id(), "clip.mp4");
        assertNull(organization.trashPhoto(a.id(), video.id()).previewUrl());
    }

    @Test void favoritesAreIdempotentPersistThroughEditsAndStayPrivate() throws IOException {
        var a = album("Trip"); var p = upload(a.id(), "a.jpg");
        assertFalse(p.favorite());
        organization.favorite(a.id(), p.id(), new FavoriteRequest(true)); organization.favorite(a.id(), p.id(), new FavoriteRequest(true));
        assertEquals(1, repository.listMetadata(a.id(), "FAVORITE#").size());
        albums.replacePhoto(a.id(), p.id(), new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{4}));
        repository.replacePhoto(a.id(), raw(a.id(), p.id()).withStatus(PhotoStatus.READY));
        assertTrue(albums.getAlbum(a.id()).photos().getFirst().favorite());
        var share = albums.createShare(a.id(), new CreateShare(1, DurationUnit.DAYS));
        assertFalse(albums.sharedAlbum(share.token()).photos().getFirst().favorite());
        organization.favorite(a.id(), p.id(), new FavoriteRequest(false));
        assertFalse(albums.listPhotos(a.id()).getFirst().favorite());
        status(HttpStatus.BAD_REQUEST, () -> organization.favorite(a.id(), p.id(), new FavoriteRequest(null)));
    }

    @Test void manualOrderRequiresEveryPhotoExactlyOnceAndSharedViewsUseIt() throws IOException {
        var a = album("Trip"); var first = upload(a.id(), "one.jpg"); var second = upload(a.id(), "two.mp4");
        organization.order(a.id(), new OrderRequest(List.of(second.id(), first.id())));
        assertEquals(List.of(second.id(), first.id()), albums.getAlbum(a.id()).photoOrder());
        var share = albums.createShare(a.id(), new CreateShare(1, DurationUnit.DAYS));
        assertEquals(albums.getAlbum(a.id()).photoOrder(), albums.sharedAlbum(share.token()).photoOrder());
        status(HttpStatus.CONFLICT, () -> organization.order(a.id(), new OrderRequest(List.of(first.id(), first.id()))));
        status(HttpStatus.CONFLICT, () -> organization.order(a.id(), new OrderRequest(List.of(first.id()))));
        upload(a.id(), "new.jpg");
        assertEquals(3, albums.getAlbum(a.id()).photos().size());
        assertEquals(2, albums.getAlbum(a.id()).photoOrder().size());
    }

    @Test void trashAndRestoreRetainPixelsRecipesHistoryFavoritesAndOrder() throws IOException {
        var a = album("Trip"); var p = upload(a.id(), "a.jpg");
        organization.favorite(a.id(), p.id(), new FavoriteRequest(true));
        organization.order(a.id(), new OrderRequest(List.of(p.id())));
        albums.replacePhoto(a.id(), p.id(), new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{4, 5}));
        repository.replacePhoto(a.id(), raw(a.id(), p.id()).withStatus(PhotoStatus.READY));
        var history = new PhotoHistory(repository, storage);
        history.saveRecipe(a.id(), p.id(), 2, Map.of("baseVersion", 1, "adjust", Map.of("brightness", 25)));
        var deleted = organization.trashPhoto(a.id(), p.id());
        assertTrue(albums.getAlbum(a.id()).photos().isEmpty());
        assertFalse(repository.replacePhotoIfCurrent(a.id(), raw(a.id(), p.id()).withStatus(PhotoStatus.READY), raw(a.id(), p.id()).objectKey()));
        assertTrue(storage.size(raw(a.id(), p.id()).objectKey()).isPresent());
        assertEquals(1, organization.trash().size());
        organization.restore(deleted.id());
        assertArrayEquals(new byte[]{4, 5}, storage.open(raw(a.id(), p.id()).objectKey()).readAllBytes());
        assertEquals(2, history.versions(a.id(), raw(a.id(), p.id())).size());
        assertEquals(25, ((Map<?, ?>) history.recipe(a.id(), p.id(), 2).get("adjust")).get("brightness"));
        assertTrue(albums.getAlbum(a.id()).photos().getFirst().favorite());
        assertEquals(List.of(p.id()), albums.getAlbum(a.id()).photoOrder());
        assertTrue(organization.trash().isEmpty());
    }

    @Test void albumRestoreKeepsPresentationAndNeverRevivesOldShareLinks() throws IOException {
        var a = album("Trip"); var p = upload(a.id(), "a.jpg");
        repository.putMetadata(a.id(), "PRESENTATION", PhotoHistory.JSON.writeValueAsString(new CreativeController.Presentation("dark", p.id(), null, "Brand", "", 5)));
        var share = albums.createShare(a.id(), new CreateShare(1, DurationUnit.DAYS));
        var deleted = organization.trashAlbum(a.id());
        assertTrue(albums.listAlbums().isEmpty()); status(HttpStatus.NOT_FOUND, () -> albums.getAlbum(a.id()));
        status(HttpStatus.NOT_FOUND, () -> albums.updateAlbum(a.id(), new UpdateAlbum("Hidden", null)));
        status(HttpStatus.NOT_FOUND, () -> albums.sharedAlbum(share.token()));
        organization.restore(deleted.id());
        assertEquals("dark", albums.getAlbum(a.id()).presentation().theme());
        assertEquals(1, albums.getAlbum(a.id()).photos().size());
        status(HttpStatus.NOT_FOUND, () -> albums.sharedAlbum(share.token()));
        assertTrue(albums.getAlbum(a.id()).shares().isEmpty());
    }

    @Test void nestedTrashRequiresRestoringAlbumAndPermanentAlbumDeletionClearsBothEntries() throws IOException {
        var a = album("Trip"); var p = upload(a.id(), "a.jpg");
        var photo = organization.trashPhoto(a.id(), p.id()); var album = organization.trashAlbum(a.id());
        status(HttpStatus.CONFLICT, () -> organization.restore(photo.id()));
        organization.restore(album.id());
        assertTrue(albums.getAlbum(a.id()).photos().isEmpty());
        organization.restore(photo.id());
        assertEquals(1, albums.getAlbum(a.id()).photos().size());
        String abandoned = "originals/" + a.id() + "/abandoned/v1.jpg";
        storage.putObject(abandoned, "image/jpeg", 1, new java.io.ByteArrayInputStream(new byte[]{9}));
        organization.trashPhoto(a.id(), p.id()); var again = organization.trashAlbum(a.id());
        organization.purge(again.id());
        assertTrue(repository.findDeletedById(a.id()).isEmpty()); assertTrue(organization.trash().isEmpty());
        assertTrue(storage.size(AlbumController.originalKey(a.id(), p.id(), 1, "jpg")).isEmpty());
        assertTrue(storage.size(abandoned).isEmpty());
    }

    @Test void expiredPhotosCannotBeRestoredAndArePurgedWithoutDiscardingRetryRecordsEarly() throws IOException {
        var a = album("Trip"); var p = upload(a.id(), "a.jpg"); var deleted = organization.trashPhoto(a.id(), p.id());
        var e = PhotoHistory.JSON.readValue(repository.listTrash(deleted.id().toString()).get(deleted.id().toString()), TrashEntry.class);
        repository.putTrash(e.id().toString(), PhotoHistory.JSON.writeValueAsString(new TrashEntry(e.id(), e.albumId(), e.photoId(), e.albumName(), e.name(), e.kind(), e.count(), e.status(), e.deletedAt(), Instant.now().minusSeconds(1))));
        status(HttpStatus.GONE, () -> organization.restore(e.id()));
        assertTrue(repository.findPhotoIncludingDeleted(a.id(), p.id()).isEmpty());
        assertTrue(storage.size(AlbumController.originalKey(a.id(), p.id(), 1, "jpg")).isEmpty());
        assertTrue(repository.listTrash("").isEmpty());
    }

    @Test void failedPurgeCanBeRetriedButCannotRaceWithRestore() throws IOException {
        var a = album("Trip"); var p = upload(a.id(), "a.jpg"); var deleted = organization.trashPhoto(a.id(), p.id());
        var flaky = new InMemoryMediaStorage() { @Override public void delete(String key) { throw new IllegalStateException("storage unavailable"); } };
        assertThrows(IllegalStateException.class, () -> organization(flaky).purge(deleted.id()));
        assertEquals(PhotoStatus.PURGING, raw(a.id(), p.id()).status());
        assertEquals(1, repository.listTrash("").size());
        status(HttpStatus.CONFLICT, () -> organization.restore(deleted.id()));
        organization.purge(deleted.id()); assertTrue(organization.trash().isEmpty());
    }

    @Test void transferCopiesEveryVersionAndRecipeIndependentlyAndMoveRemovesSourceOnlyOnSuccess() throws IOException {
        var a = album("Source"); var destination = album("Destination"); var p = upload(a.id(), "a.jpg");
        albums.replacePhoto(a.id(), p.id(), new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{4}));
        repository.replacePhoto(a.id(), raw(a.id(), p.id()).withStatus(PhotoStatus.READY));
        var history = new PhotoHistory(repository, storage); history.saveRecipe(a.id(), p.id(), 2, Map.of("baseVersion", 1, "adjust", Map.of("brightness", 25)));
        organization.favorite(a.id(), p.id(), new FavoriteRequest(true));
        var copy = organization.transfer(a.id(), p.id(), new TransferRequest(destination.id(), "copy", 2));
        assertTrue(copy.favorite()); assertEquals(2, copy.version());
        assertEquals(2, history.versions(destination.id(), raw(destination.id(), copy.id())).size());
        assertNotNull(history.recipe(destination.id(), copy.id(), 2));
        var moved = organization.transfer(a.id(), p.id(), new TransferRequest(destination.id(), "move", 2));
        assertTrue(albums.getAlbum(a.id()).photos().isEmpty());
        albums.deleteAlbum(a.id());
        assertArrayEquals(new byte[]{4}, storage.open(raw(destination.id(), moved.id()).objectKey()).readAllBytes());
        assertArrayEquals(new byte[]{1, 2, 3}, storage.open(history.versions(destination.id(), raw(destination.id(), copy.id())).get(1).objectKey()).readAllBytes());
    }

    @Test void missingSourceBytesAndConcurrentEditsNeverLoseSourceOrLeavePartialCopies() throws IOException {
        var a = album("Source"); var destination = album("Destination"); var p = upload(a.id(), "a.jpg");
        storage.delete(raw(a.id(), p.id()).objectKey());
        assertThrows(IllegalStateException.class, () -> organization.transfer(a.id(), p.id(), new TransferRequest(destination.id(), "move", 1)));
        assertEquals(1, albums.getAlbum(a.id()).photos().size()); assertTrue(albums.getAlbum(destination.id()).photos().isEmpty());
        var second = upload(a.id(), "second.jpg");
        var raced = new InMemoryMediaStorage() {
            @Override public String copyObject(String key, String dest, String type) {
                String result = storage.copyObject(key, dest, type);
                repository.replacePhoto(a.id(), new Photo(second.id(), "second.jpg", "image/jpeg", 3, "new-version", Instant.now(), null));
                return result;
            }
            @Override public Optional<Long> size(String key) { return storage.size(key); }
            @Override public void delete(String key) { storage.delete(key); }
            @Override public void deletePrefix(String prefix) { storage.deletePrefix(prefix); }
        };
        status(HttpStatus.CONFLICT, () -> organization(raced).transfer(a.id(), second.id(), new TransferRequest(destination.id(), "move", 1)));
        assertEquals("new-version", raw(a.id(), second.id()).objectKey()); assertTrue(albums.getAlbum(destination.id()).photos().isEmpty());
    }

    @Test void currentRecordAndRecipeExistBeforeS3CopyCanDispatchProcessing() throws IOException {
        var a = album("Source"); var destination = album("Destination"); var p = upload(a.id(), "a.jpg");
        new PhotoHistory(repository, storage).saveRecipe(a.id(), p.id(), 1, Map.of("baseVersion", 1));
        var observed = new AtomicBoolean();
        var events = new InMemoryMediaStorage() {
            @Override public String copyObject(String key, String dest, String type) {
                if (type.equals("image/jpeg")) {
                    var row = repository.findById(destination.id()).orElseThrow().photos().getFirst();
                    assertEquals(dest, row.objectKey());
                    assertTrue(storage.size(PhotoHistory.recipeKey(destination.id(), row.id(), 1)).isPresent());
                    observed.set(true);
                }
                return storage.copyObject(key, dest, type);
            }
            @Override public Optional<Long> size(String key) { return storage.size(key); }
        };
        organization(events).transfer(a.id(), p.id(), new TransferRequest(destination.id(), "copy", 1));
        assertTrue(observed.get());
    }

    @Test void failedEditRollbackKeepsAConcurrentlyTrashedPhotoHiddenAndRecoverable() throws IOException {
        var a = album("Source"); var p = upload(a.id(), "a.jpg");
        var request = new CreateUpload("a.jpg", "image/jpeg", 3L); UUID upload = UUID.randomUUID();
        String staged = "replacement-uploads/" + a.id() + "/" + p.id() + "/" + upload + ".jpg";
        storage.putObject(staged, "image/jpeg", 3, new java.io.ByteArrayInputStream(new byte[]{4, 5, 6}));
        var failing = new InMemoryMediaStorage() {
            @Override public String copyObject(String key, String dest, String type) {
                organization.trashPhoto(a.id(), p.id());
                throw new IllegalStateException("copy failed");
            }
            @Override public Optional<Long> size(String key) { return storage.size(key); }
            @Override public void delete(String key) { storage.delete(key); }
        };
        var editor = new AlbumController(repository, failing,
                new LocalUploadUrlSigner(new MediaProperties(Duration.ofHours(6), Duration.ofMinutes(15))), new ApiMediaUrlSigner(), (x, y) -> {});
        assertThrows(IllegalStateException.class, () -> editor.completeReplacementUpload(a.id(), p.id(), upload, 2, request));
        assertEquals(PhotoStatus.TRASHED, raw(a.id(), p.id()).status());
        assertEquals(1, PhotoHistory.version(raw(a.id(), p.id())));
        assertTrue(albums.getAlbum(a.id()).photos().isEmpty());
        var deleted = organization.trash().getFirst(); organization.restore(deleted.id());
        assertArrayEquals(new byte[]{1, 2, 3}, storage.open(raw(a.id(), p.id()).objectKey()).readAllBytes());
    }
}
