package net.samitkumar.photo_album_aws.repository;

import net.samitkumar.photo_album_aws.controller.AlbumController.Album;
import net.samitkumar.photo_album_aws.controller.AlbumController.Photo;
import net.samitkumar.photo_album_aws.controller.AlbumController.PhotoStatus;
import net.samitkumar.photo_album_aws.controller.AlbumController.Share;
import net.samitkumar.photo_album_aws.controller.AlbumController.ShareSummary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Keeps everything in process memory. The default, for local development and fast tests; data is lost on restart.
 * Each album is replaced atomically through {@link ConcurrentHashMap#compute}, so concurrent changes cannot interleave.
 */
@Component
@ConditionalOnProperty(prefix = "spring.application.data", name = "mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryAlbumRepository implements AlbumRepository {
    private final Map<UUID, Album> albums = new ConcurrentHashMap<>();
    private final Map<String, Share> shares = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, String>> metadata = new ConcurrentHashMap<>();
    private final Map<String, String> trash = new ConcurrentHashMap<>();
    private final Set<UUID> deletedAlbums = ConcurrentHashMap.newKeySet();
    private final Set<UUID> purgingAlbums = ConcurrentHashMap.newKeySet();

    @Override
    public List<Album> findAll() {
        return albums.values().stream().filter(a -> !deletedAlbums.contains(a.id())).map(InMemoryAlbumRepository::visible)
                .sorted(Comparator.comparing(Album::createdAt).reversed()).toList();
    }

    @Override
    public Optional<Album> findById(UUID albumId) { return deletedAlbums.contains(albumId) ? Optional.empty() : Optional.ofNullable(albums.get(albumId)).map(InMemoryAlbumRepository::visible); }

    private static Album visible(Album album) { return withPhotos(album, album.photos().stream().filter(p -> p.status() != PhotoStatus.TRASHED && p.status() != PhotoStatus.PURGING).toList()); }
    @Override public Optional<Album> findDeletedById(UUID albumId) { return deletedAlbums.contains(albumId) ? Optional.ofNullable(albums.get(albumId)) : Optional.empty(); }
    @Override public Optional<Photo> findPhotoIncludingDeleted(UUID albumId, UUID photoId) {
        return Optional.ofNullable(albums.get(albumId)).stream().flatMap(a -> a.photos().stream()).filter(p -> p.id().equals(photoId)).findFirst();
    }
    @Override public synchronized boolean setAlbumDeleted(UUID albumId, boolean expected, boolean deleted) {
        var changed = new AtomicBoolean();
        albums.computeIfPresent(albumId, (id, album) -> {
            if (deletedAlbums.contains(id) != expected || !deleted && purgingAlbums.contains(id)) return album;
            if (expected && deleted) purgingAlbums.add(id);
            if (deleted) deletedAlbums.add(id); else deletedAlbums.remove(id);
            changed.set(true); return album;
        });
        return changed.get();
    }

    @Override
    public void create(Album album) {
        albums.put(album.id(), new Album(album.id(), album.name(), album.description(), album.createdAt(), List.of()));
    }

    @Override
    public Optional<Album> update(UUID albumId, String name, String description) {
        var updated = new AtomicReference<Album>();
        albums.computeIfPresent(albumId, (id, a) -> {
            if (deletedAlbums.contains(id)) return a;
            var next = new Album(id, name == null ? a.name() : name, description == null ? a.description() : description, a.createdAt(), a.photos());
            updated.set(visible(next)); return next;
        });
        return Optional.ofNullable(updated.get());
    }

    @Override
    public synchronized Optional<Album> delete(UUID albumId) {
        deletedAlbums.remove(albumId);
        purgingAlbums.remove(albumId);
        metadata.remove(albumId);
        shares.values().removeIf(share -> share.albumId().equals(albumId));
        return Optional.ofNullable(albums.remove(albumId));
    }

    @Override
    public boolean addPhoto(UUID albumId, Photo photo) {
        var added = new AtomicBoolean();
        albums.computeIfPresent(albumId, (id, a) -> {
            if (deletedAlbums.contains(id) || a.photos().stream().anyMatch(p -> p.id().equals(photo.id()))) return a;
            added.set(true);
            return withPhotos(a, Stream.concat(a.photos().stream(), Stream.of(photo)).toList());
        });
        return added.get();
    }

    @Override
    public boolean replacePhoto(UUID albumId, Photo photo) {
        var replaced = new AtomicBoolean();
        albums.computeIfPresent(albumId, (id, a) -> withPhotos(a, a.photos().stream().map(p -> {
            if (!p.id().equals(photo.id())) return p;
            replaced.set(true);
            return photo;
        }).toList()));
        return replaced.get();
    }

    @Override
    public synchronized boolean replacePhotoIfCurrent(UUID albumId, Photo photo, String expectedObjectKey) {
        var replaced = new AtomicBoolean();
        albums.computeIfPresent(albumId, (id, a) -> withPhotos(a, a.photos().stream().map(p -> {
            if (purgingAlbums.contains(id) || !p.id().equals(photo.id()) || p.status() == PhotoStatus.PURGING
                    || (p.status() == PhotoStatus.TRASHED && photo.status() != PhotoStatus.TRASHED) || !Objects.equals(p.objectKey(), expectedObjectKey)) return p;
            replaced.set(true);
            return photo;
        }).toList()));
        return replaced.get();
    }

    @Override
    public synchronized Optional<Photo> updatePhotoStatus(UUID albumId, UUID photoId, PhotoStatus expected, PhotoStatus next) {
        var updated = new AtomicReference<Photo>();
        albums.computeIfPresent(albumId, (id, a) -> withPhotos(a, a.photos().stream().map(p -> {
            if (!p.id().equals(photoId) || p.status() != expected) return p;
            updated.set(p.withStatus(next));
            return updated.get();
        }).toList()));
        return Optional.ofNullable(updated.get());
    }

    @Override
    public synchronized Optional<Photo> deletePhoto(UUID albumId, UUID photoId) {
        return removePhoto(albumId, photoId, null);
    }

    @Override
    public synchronized Optional<Photo> deletePhotoIfCurrent(UUID albumId, UUID photoId, String expectedObjectKey) {
        return removePhoto(albumId, photoId, Objects.requireNonNull(expectedObjectKey));
    }

    @Override public synchronized boolean commitPhotoMove(UUID sourceAlbumId, UUID photoId, String expectedObjectKey, UUID destinationAlbumId, UUID destinationPhotoId) {
        if (findById(sourceAlbumId).isEmpty() || findById(destinationAlbumId).isEmpty()) return false;
        var destination = findPhotoIncludingDeleted(destinationAlbumId, destinationPhotoId);
        if (destination.isEmpty() || destination.get().status() == PhotoStatus.TRASHED || destination.get().status() == PhotoStatus.PURGING) return false;
        return deletePhotoIfCurrent(sourceAlbumId, photoId, expectedObjectKey).isPresent();
    }

    private Optional<Photo> removePhoto(UUID albumId, UUID photoId, String expectedObjectKey) {
        var removed = new AtomicReference<Photo>();
        albums.computeIfPresent(albumId, (id, a) -> withPhotos(a, a.photos().stream().filter(p -> {
            if (!p.id().equals(photoId) || expectedObjectKey != null && (p.status() == PhotoStatus.TRASHED || p.status() == PhotoStatus.PURGING || !expectedObjectKey.equals(p.objectKey()))) return true;
            removed.set(p);
            return false;
        }).toList()));
        return Optional.ofNullable(removed.get());
    }

    @Override
    public void saveShare(String token, Share share) { shares.put(token, share); }

    @Override
    public Optional<Share> findShare(String token) { return Optional.ofNullable(shares.get(token)); }

    @Override
    public void deleteShare(String token) { shares.remove(token); }

    @Override
    public List<ShareSummary> listShares(UUID albumId) {
        return shares.entrySet().stream()
                .filter(e -> e.getValue().albumId().equals(albumId))
                .map(e -> new ShareSummary(e.getKey(), e.getValue().albumId(), e.getValue().expiresAt()))
                .toList();
    }

    private static Album withPhotos(Album a, List<Photo> photos) {
        var sorted = photos.stream().sorted(Comparator.comparing(Photo::uploadedAt).thenComparing(p -> p.id().toString())).toList();
        return new Album(a.id(), a.name(), a.description(), a.createdAt(), sorted);
    }

    @Override public void putMetadata(UUID albumId, String key, String json) {
        albums.computeIfPresent(albumId, (id, album) -> {
            metadata.computeIfAbsent(id, ignored -> new ConcurrentHashMap<>()).put(key, json);
            return album;
        });
    }
    @Override public Optional<String> getMetadata(UUID albumId, String key) {
        return Optional.ofNullable(metadata.getOrDefault(albumId, Map.of()).get(key));
    }
    @Override public Map<String, String> listMetadata(UUID albumId, String prefix) {
        var result = new TreeMap<String, String>();
        metadata.getOrDefault(albumId, Map.of()).forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
        return result;
    }
    @Override public void deleteMetadata(UUID albumId, String prefix) {
        var items = metadata.get(albumId);
        if (items != null) items.keySet().removeIf(key -> key.startsWith(prefix));
    }

    @Override public void putTrash(String key, String json) { trash.put(key, json); }
    @Override public Map<String, String> listTrash(String prefix) {
        var result = new TreeMap<String, String>();
        trash.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
        return result;
    }
    @Override public void deleteTrash(String prefix) { trash.keySet().removeIf(key -> key.startsWith(prefix)); }
}
