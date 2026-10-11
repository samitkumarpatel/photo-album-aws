package net.samitkumar.photo_album_aws;
import net.samitkumar.photo_album_aws.controller.AlbumController;
import net.samitkumar.photo_album_aws.repository.AlbumRepository;

import net.samitkumar.photo_album_aws.controller.AlbumController.Album;
import net.samitkumar.photo_album_aws.controller.AlbumController.Photo;
import net.samitkumar.photo_album_aws.controller.AlbumController.PhotoStatus;
import net.samitkumar.photo_album_aws.controller.AlbumController.Share;
import net.samitkumar.photo_album_aws.controller.AlbumController.ShareSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Behaviour every {@link AlbumRepository} must have; run against each implementation. */
abstract class AlbumRepositoryContract {

    protected abstract AlbumRepository repository();

    @Test void deletedAlbumsAndPhotosRetainTheirRowsButAreHiddenFromNormalReads() {
        var album = album("Recovery", Instant.now()); var photo = photo(Instant.now());
        repository().create(album); assertTrue(repository().addPhoto(album.id(), photo));
        repository().putMetadata(album.id(), "FAVORITE#" + photo.id(), "true");
        assertTrue(repository().updatePhotoStatus(album.id(), photo.id(), PhotoStatus.READY, PhotoStatus.TRASHED).isPresent());
        assertTrue(repository().findById(album.id()).orElseThrow().photos().isEmpty());
        assertFalse(repository().replacePhotoIfCurrent(album.id(), photo, photo.objectKey()));
        assertTrue(repository().findPhotoIncludingDeleted(album.id(), photo.id()).isPresent());
        assertTrue(repository().setAlbumDeleted(album.id(), false, true));
        assertTrue(repository().findById(album.id()).isEmpty());
        assertTrue(repository().findAll().stream().noneMatch(a -> a.id().equals(album.id())));
        assertTrue(repository().findDeletedById(album.id()).isPresent());
        assertFalse(repository().addPhoto(album.id(), photo(Instant.now())));
        assertTrue(repository().setAlbumDeleted(album.id(), true, false));
        assertTrue(repository().getMetadata(album.id(), "FAVORITE#" + photo.id()).isPresent());
        assertTrue(repository().updatePhotoStatus(album.id(), photo.id(), PhotoStatus.TRASHED, PhotoStatus.READY).isPresent());
        assertEquals(1, repository().findById(album.id()).orElseThrow().photos().size());
        repository().delete(album.id());
    }

    @Test void purgeLocksBlockRestoreAndTrashRecordsSurviveAlbumRemoval() {
        var album = album("Recovery", Instant.now()); repository().create(album);
        String key = UUID.randomUUID().toString(); repository().putTrash(key, "recovery record");
        assertTrue(repository().setAlbumDeleted(album.id(), false, true));
        assertTrue(repository().setAlbumDeleted(album.id(), true, true));
        assertFalse(repository().setAlbumDeleted(album.id(), true, false));
        repository().delete(album.id());
        assertEquals("recovery record", repository().listTrash(key).get(key));
        repository().deleteTrash(key); assertFalse(repository().listTrash("").containsKey(key));
    }

    @Test void conditionalRemovalAndDuplicateInsertionProtectOriginals() {
        var album = album("Transfers", Instant.now()); var photo = photo(Instant.now()); repository().create(album);
        assertTrue(repository().addPhoto(album.id(), photo)); assertFalse(repository().addPhoto(album.id(), photo));
        assertTrue(repository().deletePhotoIfCurrent(album.id(), photo.id(), "stale-original").isEmpty());
        assertEquals(photo, repository().deletePhotoIfCurrent(album.id(), photo.id(), photo.objectKey()).orElseThrow());
        assertTrue(repository().findById(album.id()).orElseThrow().photos().isEmpty()); repository().delete(album.id());
    }

    @Test void moveCommitRequiresLiveDestinationAndPurgeBlocksLateWorkers() {
        var source = album("Source", Instant.now()); var target = album("Target", Instant.now());
        var photo = photo(Instant.now()); var copy = photo(Instant.now());
        repository().create(source); repository().create(target);
        repository().addPhoto(source.id(), photo); repository().addPhoto(target.id(), copy);
        repository().setAlbumDeleted(target.id(), false, true);
        assertFalse(repository().commitPhotoMove(source.id(), photo.id(), photo.objectKey(), target.id(), copy.id()));
        assertTrue(repository().findPhotoIncludingDeleted(source.id(), photo.id()).isPresent());
        repository().setAlbumDeleted(target.id(), true, false);
        assertTrue(repository().commitPhotoMove(source.id(), photo.id(), photo.objectKey(), target.id(), copy.id()));
        assertTrue(repository().findPhotoIncludingDeleted(source.id(), photo.id()).isEmpty());
        repository().setAlbumDeleted(target.id(), false, true); repository().setAlbumDeleted(target.id(), true, true);
        assertFalse(repository().replacePhotoIfCurrent(target.id(), copy, copy.objectKey()));
        repository().delete(source.id()); repository().delete(target.id());
    }

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Test
    void createdAlbumsCanBeFoundAndAreListedNewestFirst() {
        var older = album("Older", NOW.minusSeconds(60));
        var newer = album("Newer", NOW);
        repository().create(older);
        repository().create(newer);

        var found = repository().findById(older.id()).orElseThrow();
        assertEquals("Older", found.name());
        assertEquals("desc", found.description());
        assertEquals(older.createdAt(), found.createdAt());
        assertTrue(found.photos().isEmpty());

        List<UUID> ids = repository().findAll().stream().map(Album::id).filter(id -> id.equals(older.id()) || id.equals(newer.id())).toList();
        assertEquals(List.of(newer.id(), older.id()), ids);
    }

    @Test
    void updateChangesOnlyGivenFieldsAndKeepsPhotos() {
        var album = album("Trip", NOW);
        repository().create(album);
        repository().addPhoto(album.id(), photo(NOW));

        var renamed = repository().update(album.id(), "Lisbon", null).orElseThrow();

        assertEquals("Lisbon", renamed.name());
        assertEquals("desc", renamed.description());
        assertEquals(1, renamed.photos().size());
        assertTrue(repository().update(UUID.randomUUID(), "x", null).isEmpty());
    }

    @Test
    void photosAreReturnedInUploadOrder() {
        var album = album("Trip", NOW);
        repository().create(album);
        var second = photo(NOW.plusSeconds(10));
        var first = photo(NOW);
        repository().addPhoto(album.id(), second);
        repository().addPhoto(album.id(), first);

        assertEquals(List.of(first, second), repository().findById(album.id()).orElseThrow().photos());
    }

    @Test
    void addingAPhotoToAMissingAlbumFails() {
        assertFalse(repository().addPhoto(UUID.randomUUID(), photo(NOW)));
    }

    @Test
    void replacingAPhotoOverwritesItOnlyIfItExists() {
        var album = album("Trip", NOW);
        repository().create(album);
        var original = photo(NOW);
        repository().addPhoto(album.id(), original);

        var edited = new Photo(original.id(), "a.jpg", "image/jpeg", 99, original.objectKey(), original.uploadedAt(), NOW.plusSeconds(5));
        assertTrue(repository().replacePhoto(album.id(), edited));
        assertEquals(List.of(edited), repository().findById(album.id()).orElseThrow().photos());
        assertFalse(repository().replacePhoto(album.id(), photo(NOW)));
    }

    @Test
    void conditionalReplaceOnlyAppliesToTheExpectedOriginal() {
        var album = album("Versions", NOW);
        repository().create(album);
        var v1 = photo(NOW);
        repository().addPhoto(album.id(), v1);
        var v2 = new Photo(v1.id(), "a.jpg", "image/jpeg", 5, v1.objectKey() + "-v2", v1.uploadedAt(), NOW.plusSeconds(5));
        assertTrue(repository().replacePhoto(album.id(), v2));

        // Results computed for v1 must not overwrite v2.
        assertFalse(repository().replacePhotoIfCurrent(album.id(), v1.withStatus(PhotoStatus.READY), v1.objectKey()));
        assertEquals(List.of(v2), repository().findById(album.id()).orElseThrow().photos());

        var ready = v2.withStatus(PhotoStatus.READY).withDerivatives("t", "d");
        assertTrue(repository().replacePhotoIfCurrent(album.id(), ready, v2.objectKey()));
        assertEquals(List.of(ready), repository().findById(album.id()).orElseThrow().photos());
        assertFalse(repository().replacePhotoIfCurrent(album.id(), photo(NOW), "key/missing"));
        assertFalse(repository().replacePhotoIfCurrent(UUID.randomUUID(), ready, v2.objectKey()));
    }

    @Test
    void deletingAPhotoReturnsIt() {
        var album = album("Trip", NOW);
        repository().create(album);
        var photo = photo(NOW);
        repository().addPhoto(album.id(), photo);

        assertEquals(photo, repository().deletePhoto(album.id(), photo.id()).orElseThrow());
        assertTrue(repository().findById(album.id()).orElseThrow().photos().isEmpty());
        assertTrue(repository().deletePhoto(album.id(), photo.id()).isEmpty());
    }

    @Test
    void deletingAnAlbumRemovesItsPhotosAndReturnsThem() {
        var album = album("Big", NOW);
        repository().create(album);
        // More photos than one DynamoDB batch (25) holds.
        for (int i = 0; i < 30; i++) repository().addPhoto(album.id(), photo(NOW.plusSeconds(i)));

        var deleted = repository().delete(album.id()).orElseThrow();

        assertEquals(30, deleted.photos().size());
        assertTrue(repository().findById(album.id()).isEmpty());
        assertTrue(repository().findAll().stream().noneMatch(a -> a.id().equals(album.id())));
        assertTrue(repository().delete(album.id()).isEmpty());
    }

    @Test
    void photoStatusMetadataAndDerivativesRoundTrip() {
        var album = album("Processed", NOW);
        repository().create(album);
        var uploading = photo(NOW).withStatus(AlbumController.PhotoStatus.UPLOADING);
        assertTrue(repository().addPhoto(album.id(), uploading));
        assertEquals(AlbumController.PhotoStatus.UPLOADING, repository().findById(album.id()).orElseThrow().photos().getFirst().status());

        var ready = uploading.withStatus(AlbumController.PhotoStatus.READY)
                .withMetadata(4000, 3000, NOW.minus(3, ChronoUnit.DAYS))
                .withDerivatives("derived/thumb.webp", "derived/display.webp");
        assertTrue(repository().replacePhoto(album.id(), ready));

        assertEquals(ready, repository().findById(album.id()).orElseThrow().photos().getFirst());
    }

    @Test
    void statusUpdatesOnlyApplyFromTheExpectedStatus() {
        var album = album("Uploads", NOW);
        repository().create(album);
        var uploading = photo(NOW).withStatus(PhotoStatus.UPLOADING);
        repository().addPhoto(album.id(), uploading);

        assertEquals(uploading.withStatus(PhotoStatus.PROCESSING),
                repository().updatePhotoStatus(album.id(), uploading.id(), PhotoStatus.UPLOADING, PhotoStatus.PROCESSING).orElseThrow());
        // A second completion, or one racing with the first, loses.
        assertTrue(repository().updatePhotoStatus(album.id(), uploading.id(), PhotoStatus.UPLOADING, PhotoStatus.PROCESSING).isEmpty());
        assertEquals(PhotoStatus.PROCESSING, repository().findById(album.id()).orElseThrow().photos().getFirst().status());
        assertTrue(repository().updatePhotoStatus(album.id(), UUID.randomUUID(), PhotoStatus.UPLOADING, PhotoStatus.PROCESSING).isEmpty());
        assertTrue(repository().updatePhotoStatus(UUID.randomUUID(), uploading.id(), PhotoStatus.PROCESSING, PhotoStatus.READY).isEmpty());
    }

    @Test
    void photosWithoutAnExplicitStatusCountAsReady() {
        var album = album("Legacy", NOW);
        repository().create(album);
        var legacy = photo(NOW);
        repository().addPhoto(album.id(), legacy);

        var processing = repository().updatePhotoStatus(album.id(), legacy.id(), PhotoStatus.READY, PhotoStatus.PROCESSING).orElseThrow();
        assertEquals(PhotoStatus.PROCESSING, processing.status());
        assertEquals(legacy.objectKey(), processing.objectKey());
    }

    @Test
    void sharesCanBeSavedFoundAndDeleted() {
        var share = new Share(UUID.randomUUID(), NOW.plus(1, ChronoUnit.DAYS));
        String token = "token-" + UUID.randomUUID();
        repository().saveShare(token, share);

        assertEquals(share, repository().findShare(token).orElseThrow());
        repository().deleteShare(token);
        assertTrue(repository().findShare(token).isEmpty());
    }

    @Test
    void listSharesReturnsOnlyThatAlbumsLinks() {
        UUID albumId = UUID.randomUUID();
        UUID otherAlbumId = UUID.randomUUID();
        String tokenA = "token-a-" + UUID.randomUUID();
        String tokenB = "token-b-" + UUID.randomUUID();
        String otherToken = "token-other-" + UUID.randomUUID();
        repository().saveShare(tokenA, new Share(albumId, NOW.plus(1, ChronoUnit.DAYS)));
        repository().saveShare(tokenB, new Share(albumId, NOW.plus(2, ChronoUnit.DAYS)));
        repository().saveShare(otherToken, new Share(otherAlbumId, NOW.plus(1, ChronoUnit.DAYS)));

        List<ShareSummary> shares = repository().listShares(albumId);

        assertEquals(2, shares.size());
        assertTrue(shares.stream().allMatch(s -> s.albumId().equals(albumId)));
        assertTrue(shares.stream().map(ShareSummary::token).toList().containsAll(List.of(tokenA, tokenB)));

        repository().deleteShare(tokenA);
        repository().deleteShare(tokenB);
        repository().deleteShare(otherToken);
    }

    protected static Album album(String name, Instant createdAt) {
        return new Album(UUID.randomUUID(), name, "desc", createdAt, List.of());
    }

    protected static Photo photo(Instant uploadedAt) {
        UUID id = UUID.randomUUID();
        return new Photo(id, "a.png", "image/png", 3, "key/" + id, uploadedAt, null);
    }
}
