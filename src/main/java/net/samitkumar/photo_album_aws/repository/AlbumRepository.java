package net.samitkumar.photo_album_aws.repository;

import net.samitkumar.photo_album_aws.controller.AlbumController.Album;
import net.samitkumar.photo_album_aws.controller.AlbumController.Photo;
import net.samitkumar.photo_album_aws.controller.AlbumController.PhotoStatus;
import net.samitkumar.photo_album_aws.controller.AlbumController.Share;
import net.samitkumar.photo_album_aws.controller.AlbumController.ShareSummary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for albums, their photos, and share links.
 *
 * <p>Every operation that depends on another record existing is atomic in the implementation: adding a photo to an
 * album deleted a moment earlier fails instead of leaving an orphan. Albums are returned with their photos sorted by
 * upload time.
 */
public interface AlbumRepository {

    /** All albums with their photos, newest album first. */
    List<Album> findAll();

    Optional<Album> findById(UUID albumId);

    /** Stores a new album. Its photo list is ignored. */
    void create(Album album);

    /** Changes name and/or description; a null argument keeps the current value. Empty if the album does not exist. */
    Optional<Album> update(UUID albumId, String name, String description);

    /** Removes the album and its photo records, returning what was removed so media can be cleaned up. */
    Optional<Album> delete(UUID albumId);

    /** Adds a photo record. False if the album no longer exists. */
    boolean addPhoto(UUID albumId, Photo photo);

    /** Overwrites an existing photo record. False if the photo no longer exists. */
    boolean replacePhoto(UUID albumId, Photo photo);

    /**
     * Overwrites a photo record only if it still points at {@code expectedObjectKey}, so work done for one original
     * cannot land on top of a newer version written meanwhile. False if the photo is gone or has another original.
     */
    boolean replacePhotoIfCurrent(UUID albumId, Photo photo, String expectedObjectKey);

    /**
     * Moves a photo from {@code expected} to {@code next} status, only if it is still in {@code expected}; a photo
     * without a stored status counts as {@code READY}. Returns the updated photo, or empty if the photo does not exist
     * or is in another status (someone else moved it first).
     */
    Optional<Photo> updatePhotoStatus(UUID albumId, UUID photoId, PhotoStatus expected, PhotoStatus next);

    /** Removes a photo record and returns it. Empty if it did not exist. */
    Optional<Photo> deletePhoto(UUID albumId, UUID photoId);

    void saveShare(String token, Share share);

    Optional<Share> findShare(String token);

    void deleteShare(String token);

    /** Every share link created for this album, expired or not; the caller filters by {@code expiresAt}. */
    List<ShareSummary> listShares(UUID albumId);
}
