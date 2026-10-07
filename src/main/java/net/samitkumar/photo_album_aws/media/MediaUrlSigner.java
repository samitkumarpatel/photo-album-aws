package net.samitkumar.photo_album_aws.media;

import net.samitkumar.photo_album_aws.controller.AlbumController.Photo;

import java.time.Instant;

/** Issues the URLs clients use to load media, so bytes need not pass through the API (and Lambda's 6 MB limit). */
public interface MediaUrlSigner {

    /**
     * A URL for one rendition of a photo.
     *
     * @param objectKey storage key serving the rendition, from {@link MediaSize#key}
     * @param apiPath   API route of this photo (owner or shared), for signers that point back at the API
     * @param notAfter  latest moment the URL may still work, such as a share link's expiry; null for no limit
     */
    String url(Photo photo, MediaSize size, String objectKey, String apiPath, Instant notAfter);

    /**
     * A URL that downloads the original as an attachment named after the photo's filename. Signers that cannot set
     * response headers return the inline original URL.
     */
    default String downloadUrl(Photo photo, String apiPath, Instant notAfter) {
        return url(photo, MediaSize.ORIGINAL, photo.objectKey(), apiPath, notAfter);
    }

    /** {@code Content-Disposition} value that saves the file under the photo's filename, UTF-8 safe. */
    static String attachment(Photo photo) {
        return org.springframework.http.ContentDisposition.attachment()
                .filename(photo.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString();
    }

    /** True when URLs point outside the API, so the API's photo route redirects to them instead of streaming. */
    default boolean redirects() { return true; }
}
