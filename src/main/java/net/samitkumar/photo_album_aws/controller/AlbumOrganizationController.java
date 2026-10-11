package net.samitkumar.photo_album_aws.controller;

import net.samitkumar.photo_album_aws.MediaStorage;
import net.samitkumar.photo_album_aws.PhotoHistory;
import net.samitkumar.photo_album_aws.controller.AlbumController.*;
import net.samitkumar.photo_album_aws.processing.ProcessingTrigger;
import net.samitkumar.photo_album_aws.repository.AlbumRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Additive album operations. Existing permanent-delete and editing endpoints keep their contracts. */
@RestController
@RequestMapping("/api")
public class AlbumOrganizationController {
    private final AlbumRepository repository;
    private final MediaStorage storage;
    private final AlbumController albums;
    private final ProcessingTrigger processing;
    private final PhotoHistory history;

    public AlbumOrganizationController(AlbumRepository repository, MediaStorage storage, AlbumController albums, ProcessingTrigger processing) {
        this.repository = repository; this.storage = storage; this.albums = albums; this.processing = processing;
        history = new PhotoHistory(repository, storage);
    }

    private Album album(UUID id) { return repository.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Album not found")); }
    private Photo photo(UUID albumId, UUID id) {
        return album(albumId).photos().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Photo not found"));
    }
    private static ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }

    @PutMapping("/albums/{albumId}/photos/{photoId}/favorite")
    public FavoriteRequest favorite(@PathVariable UUID albumId, @PathVariable UUID photoId, @RequestBody FavoriteRequest request) {
        photo(albumId, photoId);
        if (request.favorite() == null) throw error(HttpStatus.BAD_REQUEST, "Choose a favorite state");
        if (request.favorite()) repository.putMetadata(albumId, "FAVORITE#" + photoId, "true");
        else repository.deleteMetadata(albumId, "FAVORITE#" + photoId);
        return request;
    }

    public static List<UUID> photoOrder(AlbumRepository repository, Album album) {
        var existing = new HashSet<>(album.photos().stream().map(Photo::id).toList());
        return repository.getMetadata(album.id(), "ORDER").map(json -> Arrays.stream(PhotoHistory.JSON.readValue(json, UUID[].class))
                .filter(existing::contains).distinct().toList()).orElse(List.of());
    }

    @PutMapping("/albums/{albumId}/order")
    public OrderRequest order(@PathVariable UUID albumId, @RequestBody OrderRequest request) {
        var ids = album(albumId).photos().stream().map(Photo::id).collect(java.util.stream.Collectors.toSet());
        if (request.photoIds() == null || request.photoIds().stream().anyMatch(Objects::isNull) || request.photoIds().size() != ids.size()
                || !new HashSet<>(request.photoIds()).equals(ids)) throw error(HttpStatus.CONFLICT, "The album changed. Reload before arranging photos.");
        repository.putMetadata(albumId, "ORDER", PhotoHistory.JSON.writeValueAsString(request.photoIds()));
        return request;
    }

    @PostMapping("/albums/{albumId}/photos/{photoId}/trash")
    public TrashView trashPhoto(@PathVariable UUID albumId, @PathVariable UUID photoId) {
        Album album = album(albumId); Photo photo = photo(albumId, photoId);
        // Pending upload cleanup stays permanent: no complete original exists to restore.
        if (photo.status() == PhotoStatus.UPLOADING) throw error(HttpStatus.CONFLICT, "Cancel this upload before deleting it.");
        var entry = new TrashEntry(UUID.randomUUID(), albumId, photoId, album.name(), photo.filename(), "photo", 1,
                photo.status(), Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
        repository.putTrash(entry.id().toString(), PhotoHistory.JSON.writeValueAsString(entry));
        if (repository.updatePhotoStatus(albumId, photoId, photo.status(), PhotoStatus.TRASHED).isEmpty()) {
            repository.deleteTrash(entry.id().toString()); throw error(HttpStatus.CONFLICT, "The photo changed. Try again.");
        }
        return view(entry);
    }

    @PostMapping("/albums/{albumId}/trash")
    public TrashView trashAlbum(@PathVariable UUID albumId) {
        Album album = album(albumId);
        var entry = new TrashEntry(UUID.randomUUID(), albumId, null, album.name(), album.name(), "album", album.photos().size(),
                null, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
        repository.putTrash(entry.id().toString(), PhotoHistory.JSON.writeValueAsString(entry));
        if (!repository.setAlbumDeleted(albumId, false, true)) {
            repository.deleteTrash(entry.id().toString()); throw error(HttpStatus.CONFLICT, "The album changed. Try again.");
        }
        revokeLinks(albumId);
        return view(entry);
    }

    private void revokeLinks(UUID albumId) {
        for (var share : repository.listShares(albumId)) {
            repository.deleteShare(share.token()); repository.deleteMetadata(albumId, "SHARE#" + share.token());
        }
    }

    private List<TrashEntry> entries() {
        return repository.listTrash("").values().stream().map(json -> PhotoHistory.JSON.readValue(json, TrashEntry.class)).toList();
    }
    private boolean isDeleted(TrashEntry entry) {
        return entry.photoId() == null ? repository.findDeletedById(entry.albumId()).isPresent()
                : repository.findPhotoIncludingDeleted(entry.albumId(), entry.photoId()).map(p -> p.status() == PhotoStatus.TRASHED || p.status() == PhotoStatus.PURGING).orElse(false);
    }

    @GetMapping("/trash")
    public List<TrashView> trash() {
        // Lambda has no persistent scheduler. Reclaim expired entries when trash is accessed;
        // keep tombstones until media deletion succeeds so transient storage errors can be retried.
        var entries = entries();
        for (var entry : entries) if (!entry.expiresAt().isAfter(Instant.now())) purgeEntry(entry);
        return entries.stream().filter(e -> e.expiresAt().isAfter(Instant.now())).filter(this::isDeleted)
                .sorted(Comparator.comparing(TrashEntry::deletedAt).reversed()).map(this::view).toList();
    }

    private TrashEntry entry(UUID id) {
        var json = repository.listTrash(id.toString()).get(id.toString());
        if (json == null) throw error(HttpStatus.NOT_FOUND, "Deleted item not found");
        return PhotoHistory.JSON.readValue(json, TrashEntry.class);
    }
    private TrashView view(TrashEntry entry) {
        return new TrashView(entry.id(), entry.albumId(), entry.photoId(), entry.albumName(), entry.name(), entry.kind(), entry.count(), entry.deletedAt(), entry.expiresAt());
    }

    @PostMapping("/trash/{id}/restore")
    public TrashView restore(@PathVariable UUID id) {
        var entry = entry(id);
        if (!entry.expiresAt().isAfter(Instant.now())) { purgeEntry(entry); throw error(HttpStatus.GONE, "The recovery period has expired"); }
        if (entry.photoId() == null) {
            revokeLinks(entry.albumId()); // never reactivate a link that survived a failed revocation
            if (!repository.setAlbumDeleted(entry.albumId(), true, false)) throw error(HttpStatus.CONFLICT, "The album is no longer recoverable. Permanent deletion may have started.");
            for (Photo photo : album(entry.albumId()).photos()) if (photo.status() == PhotoStatus.PROCESSING) processing.uploaded(entry.albumId(), photo.id());
        } else {
            if (repository.findDeletedById(entry.albumId()).isPresent()) throw error(HttpStatus.CONFLICT, "Restore the album first, then restore this photo.");
            album(entry.albumId());
            if (repository.updatePhotoStatus(entry.albumId(), entry.photoId(), PhotoStatus.TRASHED, entry.status()).isEmpty())
                throw error(HttpStatus.CONFLICT, "The photo is no longer deleted");
            if (entry.status() == PhotoStatus.PROCESSING) processing.uploaded(entry.albumId(), entry.photoId());
        }
        repository.deleteTrash(id.toString());
        return view(entry);
    }

    @DeleteMapping("/trash/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@PathVariable UUID id) { purgeEntry(entry(id)); }

    private void purgeEntry(TrashEntry entry) {
        if (entry.photoId() == null) {
            var removed = repository.findDeletedById(entry.albumId());
            if (removed.isPresent()) {
                if (!repository.setAlbumDeleted(entry.albumId(), true, true)) throw error(HttpStatus.CONFLICT, "The album is no longer deleted");
                // Delete canonical folders in batches rather than issuing several
                // list/delete calls per photo; also covers abandoned upload objects.
                var roots = List.of("originals/", "derived/", "recipes/", "replacement-uploads/").stream()
                        .map(prefix -> storage.objectKey(prefix + entry.albumId() + "/")).toList();
                for (String root : roots) storage.deletePrefix(root);
                for (Photo photo : removed.get().photos()) {
                    for (String key : new String[]{photo.objectKey(), photo.thumbnailKey(), photo.displayKey()})
                        if (key != null && roots.stream().noneMatch(key::startsWith)) storage.delete(key);
                }
                repository.delete(entry.albumId());
                for (var child : entries()) if (child.albumId().equals(entry.albumId())) repository.deleteTrash(child.id().toString());
            }
        } else {
            var removed = repository.findPhotoIncludingDeleted(entry.albumId(), entry.photoId()).filter(p -> p.status() == PhotoStatus.TRASHED || p.status() == PhotoStatus.PURGING);
            if (removed.isPresent()) {
                if (removed.get().status() == PhotoStatus.TRASHED && repository.updatePhotoStatus(entry.albumId(), entry.photoId(), PhotoStatus.TRASHED, PhotoStatus.PURGING).isEmpty())
                    throw error(HttpStatus.CONFLICT, "The photo is no longer deleted");
                purgePhotoMedia(entry.albumId(), removed.get());
                repository.deletePhoto(entry.albumId(), entry.photoId());
                history.delete(entry.albumId(), entry.photoId());
                repository.deleteMetadata(entry.albumId(), "FAVORITE#" + entry.photoId());
            }
        }
        repository.deleteTrash(entry.id().toString());
    }
    private void purgePhotoMedia(UUID albumId, Photo photo) {
        for (String key : new String[]{photo.objectKey(), photo.thumbnailKey(), photo.displayKey()}) if (key != null && !key.isBlank()) storage.delete(key);
        for (String prefix : List.of("originals/", "derived/", "recipes/", "replacement-uploads/"))
            storage.deletePrefix(storage.objectKey(prefix + albumId + "/" + photo.id() + "/"));
    }

    @PostMapping("/albums/{albumId}/photos/{photoId}/transfer")
    public PhotoResponse transfer(@PathVariable UUID albumId, @PathVariable UUID photoId, @RequestBody TransferRequest request) throws IOException {
        Photo source = photo(albumId, photoId);
        if (request.destinationAlbumId() == null) throw error(HttpStatus.BAD_REQUEST, "Choose a destination album");
        album(request.destinationAlbumId());
        if (!Set.of("copy", "move").contains(Objects.requireNonNullElse(request.mode(), "")) || albumId.equals(request.destinationAlbumId()))
            throw error(HttpStatus.BAD_REQUEST, "Choose another album and a copy or move action");
        if (source.status() != PhotoStatus.READY || request.version() == null || request.version() != PhotoHistory.version(source))
            throw error(HttpStatus.CONFLICT, "The photo changed or is not ready. Reload before transferring it.");
        UUID destination = request.destinationAlbumId(), id = UUID.randomUUID();
        boolean committed = false;
        try {
            var versions = history.versions(albumId, source);
            String currentKey = AlbumController.originalKey(destination, id, PhotoHistory.version(source), AlbumController.extension(source.filename(), source.contentType()));
            Photo current = new Photo(id, source.filename(), source.contentType(), source.size(), storage.objectKey(currentKey), Instant.now(), source.editedAt(),
                    PhotoStatus.PROCESSING, source.width(), source.height(), source.takenAt(), null, null);
            // S3 events can run as soon as a copy finishes. The row must exist before
            // writing the current original, and its recipe must exist before that event.
            if (!repository.addPhoto(destination, current)) throw error(HttpStatus.CONFLICT, "Destination album is unavailable");
            if (repository.getMetadata(albumId, "FAVORITE#" + photoId).isPresent()) repository.putMetadata(destination, "FAVORITE#" + id, "true");
            for (var version : versions.entrySet()) {
                Photo original = version.getValue();
                String relative = AlbumController.originalKey(destination, id, version.getKey(), AlbumController.extension(original.filename(), original.contentType()));
                String recipe = storage.objectKey(PhotoHistory.recipeKey(albumId, photoId, version.getKey()));
                if (storage.size(recipe).isPresent()) storage.copyObject(recipe, PhotoHistory.recipeKey(destination, id, version.getKey()), "application/json");
                String key = storage.copyObject(original.objectKey(), relative, original.contentType());
                Photo copy = new Photo(id, original.filename(), original.contentType(), original.size(), key, Instant.now(), original.editedAt(),
                        PhotoStatus.PROCESSING, original.width(), original.height(), original.takenAt(), null, null);
                if (version.getKey() != PhotoHistory.version(source)) history.archive(destination, copy);
            }
            processing.uploaded(destination, id);
            if (request.mode().equals("move")) {
                album(albumId);
                if (!repository.commitPhotoMove(albumId, photoId, source.objectKey(), destination, id))
                    throw error(HttpStatus.CONFLICT, "The photo or destination changed. The source has not been moved.");
                committed = true;
                albums.deletePhotoMedia(albumId, source); history.delete(albumId, photoId); repository.deleteMetadata(albumId, "FAVORITE#" + photoId);
            }
            committed = true;
            return albums.getAlbum(destination).photos().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
        } catch (RuntimeException e) {
            if (!committed) {
                repository.deletePhoto(destination, id);
                try { purgePhotoMedia(destination, new Photo(id, source.filename(), source.contentType(), source.size(), "", Instant.now(), null)); history.delete(destination, id); repository.deleteMetadata(destination, "FAVORITE#" + id); }
                catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            }
            throw e;
        }
    }

    public record FavoriteRequest(Boolean favorite) {}
    public record OrderRequest(List<UUID> photoIds) {}
    public record TransferRequest(UUID destinationAlbumId, String mode, Integer version) {}
    public record TrashEntry(UUID id, UUID albumId, UUID photoId, String albumName, String name, String kind, int count,
                             PhotoStatus status, Instant deletedAt, Instant expiresAt) {}
    public record TrashView(UUID id, UUID albumId, UUID photoId, String albumName, String name, String kind, int count, Instant deletedAt, Instant expiresAt) {}
}
