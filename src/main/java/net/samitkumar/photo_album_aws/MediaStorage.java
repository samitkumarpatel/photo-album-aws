package net.samitkumar.photo_album_aws;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Object storage for originals and derivatives. Keys passed in are relative to the storage root (for example
 * {@code originals/{albumId}/{photoId}/v1.jpg}); keys handed back and stored in the repository are full object keys.
 */
public interface MediaStorage {
    /** The full object key for a key relative to the storage root, without storing anything. */
    default String objectKey(String relativeKey) { return relativeKey; }

    /**
     * Stores content under a key relative to the storage root, for example {@code derived/{albumId}/{photoId}/thumb.webp},
     * and returns the full object key to use with {@link #open} and {@link #delete}.
     */
    String putObject(String relativeKey, String contentType, long size, InputStream content) throws IOException;

    /** Copies an object already stored under a full key to a key relative to this storage root. */
    String copyObject(String sourceObjectKey, String destinationRelativeKey, String contentType);

    InputStream open(String objectKey) throws IOException;

    /** Size in bytes of a stored object, or empty if it does not exist. */
    Optional<Long> size(String objectKey);
    void delete(String objectKey);

    /** Deletes every object whose full key starts with {@code objectKeyPrefix}. */
    void deletePrefix(String objectKeyPrefix);
}
