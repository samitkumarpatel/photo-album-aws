package net.samitkumar.photo_album_aws.media;

/**
 * Where a client loads a photo: grid thumbnail, viewer-sized display image, the full original shown inline, and the
 * original as a download under its filename (where the serving side can set Content-Disposition).
 */
public record MediaUrls(String thumbnail, String display, String original, String download) {}
