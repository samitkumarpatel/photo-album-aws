package net.samitkumar.photo_album_aws.processing;

import net.samitkumar.photo_album_aws.AlbumController.Photo;
import net.samitkumar.photo_album_aws.AlbumController.PhotoStatus;
import net.samitkumar.photo_album_aws.AlbumRepository;
import net.samitkumar.photo_album_aws.MediaStorage;
import net.samitkumar.photo_album_aws.processing.MediaSniffer.DetectedMedia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Turns a stored original into a usable photo: checks the bytes match the declared type, reads dimensions and capture
 * time, writes a thumbnail and a display-size image, and records the result.
 *
 * <p>Safe to run more than once for the same original (SQS delivers at least once) and safe against stale work: an
 * event for an older version is ignored, and results are only saved if the photo still points at the same original.
 */
public class PhotoProcessor {
    private static final Logger log = LoggerFactory.getLogger(PhotoProcessor.class);
    static final int THUMBNAIL_EDGE = 400;
    static final int DISPLAY_EDGE = 2048;
    /** A decoded 100 MP image needs about 400 MB as ARGB; anything larger is left to clients. */
    static final long MAX_DECODE_PIXELS = 100_000_000L;

    public enum Outcome { SKIPPED, READY, READY_WITHOUT_DERIVATIVES, FAILED }

    private record Derivatives(String thumbnailKey, String displayKey) {
        static final Derivatives NONE = new Derivatives(null, null);
    }

    static {
        // The disk cache would write temp files for every decoded stream; everything here already lives in memory or /tmp.
        ImageIO.setUseCache(false);
    }

    private final AlbumRepository repository;
    private final MediaStorage storage;
    private final PosterExtractor posterExtractor;
    private final Images.Encoder encoder;

    public PhotoProcessor(AlbumRepository repository, MediaStorage storage, PosterExtractor posterExtractor) {
        this.repository = repository;
        this.storage = storage;
        this.posterExtractor = posterExtractor;
        this.encoder = Images.Encoder.best();
    }

    public Outcome process(UUID albumId, UUID photoId) throws IOException {
        return process(albumId, photoId, null);
    }

    /** @param objectKey the original the caller was told about, or null for whatever the photo currently points at */
    public Outcome process(UUID albumId, UUID photoId, String objectKey) throws IOException {
        Photo photo = find(albumId, photoId).orElse(null);
        if (photo == null) {
            log.info("Skipping photo {} in album {}: it no longer exists", photoId, albumId);
            return Outcome.SKIPPED;
        }
        if (objectKey != null && !objectKey.equals(photo.objectKey())) {
            log.info("Skipping {}: photo {} now points at {}", objectKey, photoId, photo.objectKey());
            return Outcome.SKIPPED;
        }
        String derivedPrefix = derivedPrefix(albumId, photoId, photo.objectKey());
        if (photo.status() == PhotoStatus.READY && photo.thumbnailKey() != null && photo.thumbnailKey().contains(derivedPrefix)) {
            log.debug("Skipping photo {}: derivatives for {} already exist", photoId, photo.objectKey());
            return Outcome.SKIPPED;
        }

        Path file = Files.createTempFile("original-", ".bin");
        try {
            try (InputStream in = storage.open(photo.objectKey())) {
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
            Optional<DetectedMedia> detected = MediaSniffer.detect(header(file));
            if (detected.isEmpty() || !detected.get().matches(photo.contentType())) {
                log.warn("Photo {} in album {} rejected: declared {} but content is {}", photoId, albumId, photo.contentType(),
                        detected.map(Enum::name).orElse("not a supported image or video"));
                return save(albumId, photo, PhotoStatus.FAILED, MediaMetadata.NONE, Derivatives.NONE);
            }
            DetectedMedia media = detected.get();
            MediaMetadata metadata = MediaMetadata.read(file, media);
            Derivatives derivatives = derivatives(file, media, photo, metadata, derivedPrefix);
            return save(albumId, photo, PhotoStatus.READY, metadata, derivatives);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Records a failure the caller could not recover from, unless the photo has moved on to another original. */
    public void markFailed(UUID albumId, UUID photoId, String objectKey) {
        find(albumId, photoId)
                .filter(photo -> objectKey == null || objectKey.equals(photo.objectKey()))
                .ifPresent(photo -> repository.replacePhotoIfCurrent(albumId, photo.withStatus(PhotoStatus.FAILED), photo.objectKey()));
    }

    /**
     * Derivative keys include the original's version, so every edit gets new URLs: CDN and browser caches never serve
     * an old rendition, and a finished derivative can be matched to the original it came from.
     */
    static String derivedPrefix(UUID albumId, UUID photoId, String objectKey) {
        String version = OriginalKey.parse(objectKey).map(OriginalKey::version).orElse(null);
        return "derived/" + albumId + "/" + photoId + "/" + (version == null ? "" : version + "/");
    }

    private Derivatives derivatives(Path file, DetectedMedia media, Photo photo, MediaMetadata metadata, String prefix) throws IOException {
        BufferedImage source;
        int orientation = metadata.orientation();
        try {
            if (media.kind == MediaSniffer.Kind.VIDEO) {
                source = posterExtractor.extract(file, photo.contentType()).orElse(null);
                orientation = 1;
            } else {
                source = decode(file, photo);
            }
        } catch (IOException | RuntimeException e) {
            // Valid file, but Java cannot render it (CMYK JPEG, unusual TIFF, truncated data). Clients show the original.
            log.warn("Photo {}: could not decode {} for derivatives: {}", photo.id(), media, e.toString());
            return Derivatives.NONE;
        }
        if (source == null) return Derivatives.NONE;

        BufferedImage display = Images.orient(Images.fit(source, DISPLAY_EDGE), orientation);
        source.flush();
        BufferedImage thumbnail = Images.fit(display, THUMBNAIL_EDGE);
        String displayKey = put(prefix + "display." + encoder.extension(), encoder.encode(display));
        String thumbnailKey = put(prefix + "thumb." + encoder.extension(), encoder.encode(thumbnail));
        return new Derivatives(thumbnailKey, displayKey);
    }

    /**
     * Decodes with ImageIO, or returns null when no reader exists (HEIC, AVIF) or the image is too large. Huge sources
     * are subsampled while decoding down to about twice the display size, which bounds memory; the halving in
     * {@link Images#fit} then smooths away the subsampling's aliasing.
     */
    private BufferedImage decode(Path file, Photo photo) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                log.info("Photo {}: no decoder for {}, keeping it without derivatives", photo.id(), photo.contentType());
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if ((long) width * height > MAX_DECODE_PIXELS) {
                    log.info("Photo {}: {}x{} is over the decode limit, keeping it without derivatives", photo.id(), width, height);
                    return null;
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, Math.max(width, height) / (2 * DISPLAY_EDGE));
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    private String put(String relativeKey, byte[] bytes) throws IOException {
        return storage.putObject(relativeKey, encoder.contentType(), bytes.length, new ByteArrayInputStream(bytes));
    }

    /**
     * Writes the result onto a fresh copy of the photo, so a rename made meanwhile survives. If the photo was deleted
     * or replaced by another version while we worked, our derivatives are orphans and are removed instead. The write
     * is conditional on the original, which closes the gap between this check and the save.
     */
    private Outcome save(UUID albumId, Photo processed, PhotoStatus status, MediaMetadata metadata, Derivatives derivatives) {
        Photo current = find(albumId, processed.id()).orElse(null);
        if (current == null || !Objects.equals(current.objectKey(), processed.objectKey())) {
            deleteQuietly(derivatives.thumbnailKey(), derivatives.displayKey());
            log.info("Discarding results for photo {}: it was deleted or replaced while processing", processed.id());
            return Outcome.SKIPPED;
        }
        Photo updated = current.withStatus(status)
                .withMetadata(metadata.width(), metadata.height(), metadata.takenAt())
                .withDerivatives(derivatives.thumbnailKey(), derivatives.displayKey());
        if (!repository.replacePhotoIfCurrent(albumId, updated, processed.objectKey())) {
            deleteQuietly(derivatives.thumbnailKey(), derivatives.displayKey());
            log.info("Discarding results for photo {}: it was deleted or replaced while saving", processed.id());
            return Outcome.SKIPPED;
        }
        var kept = new HashSet<String>();
        if (derivatives.thumbnailKey() != null) kept.add(derivatives.thumbnailKey());
        if (derivatives.displayKey() != null) kept.add(derivatives.displayKey());
        for (String previous : new String[]{current.thumbnailKey(), current.displayKey()}) {
            if (previous != null && !kept.contains(previous)) deleteQuietly(previous);
        }
        Outcome outcome = status == PhotoStatus.FAILED ? Outcome.FAILED
                : derivatives.thumbnailKey() == null ? Outcome.READY_WITHOUT_DERIVATIVES : Outcome.READY;
        log.info("Processed photo {} in album {}: {} ({}x{})", processed.id(), albumId, outcome, metadata.width(), metadata.height());
        return outcome;
    }

    private Optional<Photo> find(UUID albumId, UUID photoId) {
        return repository.findById(albumId)
                .flatMap(album -> album.photos().stream().filter(p -> p.id().equals(photoId)).findFirst());
    }

    private static byte[] header(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(MediaSniffer.HEADER_BYTES);
        }
    }

    private void deleteQuietly(String... keys) {
        for (String key : keys) {
            if (key == null) continue;
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                log.warn("Could not delete derivative {}", key, e);
            }
        }
    }
}
