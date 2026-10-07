package net.samitkumar.photo_album_aws.media;

import net.samitkumar.photo_album_aws.AlbumController.Photo;

import java.time.Instant;

/**
 * Local stand-in: URLs back to the API's own photo routes, which stream from {@code MediaStorage}. The routes check
 * access themselves, so nothing is signed. {@code v} changes whenever the served object does, for cache busting.
 */
public class ApiMediaUrlSigner implements MediaUrlSigner {

    @Override
    public String url(Photo photo, MediaSize size, String objectKey, String apiPath, Instant notAfter) {
        Instant changed = photo.editedAt() != null ? photo.editedAt() : photo.uploadedAt();
        String version = Integer.toUnsignedString((objectKey + "@" + changed.toEpochMilli()).hashCode(), 36);
        return apiPath + "?size=" + size.param() + "&v=" + version;
    }

    @Override
    public String downloadUrl(Photo photo, String apiPath, Instant notAfter) {
        return url(photo, MediaSize.ORIGINAL, photo.objectKey(), apiPath, notAfter) + "&download=1";
    }

    @Override
    public boolean redirects() { return false; }
}
