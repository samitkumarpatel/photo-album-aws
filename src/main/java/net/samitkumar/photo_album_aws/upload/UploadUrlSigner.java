package net.samitkumar.photo_album_aws.upload;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Issues a URL the browser PUTs one file to, locked to the exact object key, content type and size, so uploads never
 * pass through the API (Lambda caps request bodies at 6 MB).
 */
public interface UploadUrlSigner {

    PresignedUpload presignPut(UUID albumId, UUID photoId, String objectKey, String contentType, long size);

    /** The URL, the headers the client must send with it (besides Content-Length, which it sets itself), and its expiry. */
    record PresignedUpload(String url, Map<String, String> headers, Instant expiresAt) {}
}
