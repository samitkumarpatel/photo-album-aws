package net.samitkumar.photo_album_aws.processing;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Pulls a still frame out of a video so it gets thumbnails like a photo. The frame must already be upright.
 *
 * <p>The default does nothing: videos are marked ready without derivatives and clients show the original. The planned
 * implementation runs ffmpeg from a Lambda layer ({@code ffmpeg -ss 1 -i in -frames:v 1 out.png}, which also applies
 * the rotation matrix); a Java binding such as JavaCV would add hundreds of megabytes to the deployment package.
 */
@FunctionalInterface
public interface PosterExtractor {
    Optional<BufferedImage> extract(Path video, String contentType) throws IOException;

    static PosterExtractor none() {
        return (video, contentType) -> Optional.empty();
    }
}
