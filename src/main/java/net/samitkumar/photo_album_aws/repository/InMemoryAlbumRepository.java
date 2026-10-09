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

    @Override
    public List<Album> findAll() {
        return albums.values().stream().sorted(Comparator.comparing(Album::createdAt).reversed()).toList();
    }

    @Override
    public Optional<Album> findById(UUID albumId) { return Optional.ofNullable(albums.get(albumId)); }

    @Override
    public void create(Album album) {
        albums.put(album.id(), new Album(album.id(), album.name(), album.description(), album.createdAt(), List.of()));
    }

    @Override
    public Optional<Album> update(UUID albumId, String name, String description) {
        return Optional.ofNullable(albums.computeIfPresent(albumId, (id, a) -> new Album(id,
                name == null ? a.name() : name, description == null ? a.description() : description, a.createdAt(), a.photos())));
    }

    @Override
    public Optional<Album> delete(UUID albumId) {
        metadata.remove(albumId);
        shares.values().removeIf(share -> share.albumId().equals(albumId));
        return Optional.ofNullable(albums.remove(albumId));
    }

    @Override
    public boolean addPhoto(UUID albumId, Photo photo) {
        return albums.computeIfPresent(albumId, (id, a) -> withPhotos(a, Stream.concat(a.photos().stream(), Stream.of(photo)).toList())) != null;
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
    public boolean replacePhotoIfCurrent(UUID albumId, Photo photo, String expectedObjectKey) {
        var replaced = new AtomicBoolean();
        albums.computeIfPresent(albumId, (id, a) -> withPhotos(a, a.photos().stream().map(p -> {
            if (!p.id().equals(photo.id()) || !Objects.equals(p.objectKey(), expectedObjectKey)) return p;
            replaced.set(true);
            return photo;
        }).toList()));
        return replaced.get();
    }

    @Override
    public Optional<Photo> updatePhotoStatus(UUID albumId, UUID photoId, PhotoStatus expected, PhotoStatus next) {
        var updated = new AtomicReference<Photo>();
        albums.computeIfPresent(albumId, (id, a) -> withPhotos(a, a.photos().stream().map(p -> {
            if (!p.id().equals(photoId) || p.status() != expected) return p;
            updated.set(p.withStatus(next));
            return updated.get();
        }).toList()));
        return Optional.ofNullable(updated.get());
    }

    @Override
    public Optional<Photo> deletePhoto(UUID albumId, UUID photoId) {
        var removed = new AtomicReference<Photo>();
        albums.computeIfPresent(albumId, (id, a) -> withPhotos(a, a.photos().stream().filter(p -> {
            if (!p.id().equals(photoId)) return true;
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
}
