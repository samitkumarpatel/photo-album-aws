package net.samitkumar.photo_album_aws.processing;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.avi.AviDirectory;
import com.drew.metadata.bmp.BmpHeaderDirectory;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.gif.GifHeaderDirectory;
import com.drew.metadata.heif.HeifDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mov.media.QuickTimeVideoDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;
import com.drew.metadata.png.PngDirectory;
import com.drew.metadata.webp.WebpDirectory;
import net.samitkumar.photo_album_aws.processing.MediaSniffer.DetectedMedia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * What a file says about itself. {@code width} and {@code height} are as displayed, after EXIF orientation or video
 * rotation; {@code orientation} is the EXIF value (1 to 8) that derivatives must apply to the stored pixels.
 */
record MediaMetadata(Integer width, Integer height, int orientation, Instant takenAt) {
    private static final Logger log = LoggerFactory.getLogger(MediaMetadata.class);
    static final MediaMetadata NONE = new MediaMetadata(null, null, 1, null);
    /** Video containers store 1904-based zeros when the camera did not set a time. */
    private static final Instant PLAUSIBLE_AFTER = Instant.parse("1971-01-01T00:00:00Z");

    private record Dimensions(Class<? extends Directory> type, int widthTag, int heightTag) {}

    /** Codec headers first: EXIF dimensions are often stale after an edit. */
    private static final List<Dimensions> CODEC_DIMENSIONS = List.of(
            new Dimensions(JpegDirectory.class, JpegDirectory.TAG_IMAGE_WIDTH, JpegDirectory.TAG_IMAGE_HEIGHT),
            new Dimensions(PngDirectory.class, PngDirectory.TAG_IMAGE_WIDTH, PngDirectory.TAG_IMAGE_HEIGHT),
            new Dimensions(GifHeaderDirectory.class, GifHeaderDirectory.TAG_IMAGE_WIDTH, GifHeaderDirectory.TAG_IMAGE_HEIGHT),
            new Dimensions(WebpDirectory.class, WebpDirectory.TAG_IMAGE_WIDTH, WebpDirectory.TAG_IMAGE_HEIGHT),
            new Dimensions(BmpHeaderDirectory.class, BmpHeaderDirectory.TAG_IMAGE_WIDTH, BmpHeaderDirectory.TAG_IMAGE_HEIGHT),
            new Dimensions(Mp4VideoDirectory.class, Mp4VideoDirectory.TAG_WIDTH, Mp4VideoDirectory.TAG_HEIGHT),
            new Dimensions(QuickTimeVideoDirectory.class, QuickTimeVideoDirectory.TAG_WIDTH, QuickTimeVideoDirectory.TAG_HEIGHT),
            new Dimensions(AviDirectory.class, AviDirectory.TAG_WIDTH, AviDirectory.TAG_HEIGHT),
            new Dimensions(ExifIFD0Directory.class, ExifDirectoryBase.TAG_IMAGE_WIDTH, ExifDirectoryBase.TAG_IMAGE_HEIGHT),
            new Dimensions(ExifSubIFDDirectory.class, ExifDirectoryBase.TAG_EXIF_IMAGE_WIDTH, ExifDirectoryBase.TAG_EXIF_IMAGE_HEIGHT));

    /**
     * HEIF stores phone photos as a grid of tiles and metadata-extractor reports the first {@code ispe} it meets, which
     * may be a tile; the EXIF pixel dimensions describe the whole image.
     */
    private static final List<Dimensions> HEIF_DIMENSIONS = List.of(
            new Dimensions(ExifSubIFDDirectory.class, ExifDirectoryBase.TAG_EXIF_IMAGE_WIDTH, ExifDirectoryBase.TAG_EXIF_IMAGE_HEIGHT),
            new Dimensions(HeifDirectory.class, HeifDirectory.TAG_IMAGE_WIDTH, HeifDirectory.TAG_IMAGE_HEIGHT));

    /** Never throws: unreadable metadata just means no metadata. */
    static MediaMetadata read(Path file, DetectedMedia media) {
        Metadata metadata;
        try {
            metadata = ImageMetadataReader.readMetadata(file.toFile());
        } catch (Exception e) {
            log.debug("No readable metadata in {} file: {}", media, e.toString());
            return NONE;
        }
        int[] size = dimensions(metadata, media == DetectedMedia.HEIC || media == DetectedMedia.HEIF || media == DetectedMedia.AVIF
                ? HEIF_DIMENSIONS : CODEC_DIMENSIONS);
        int orientation = media.kind == MediaSniffer.Kind.IMAGE ? orientation(metadata) : 1;
        boolean swap = media.kind == MediaSniffer.Kind.IMAGE ? swapsAxes(orientation) : quarterTurnVideo(metadata);
        Integer width = size == null ? null : swap ? size[1] : size[0];
        Integer height = size == null ? null : swap ? size[0] : size[1];
        return new MediaMetadata(width, height, orientation, takenAt(metadata));
    }

    static boolean swapsAxes(int orientation) { return orientation >= 5 && orientation <= 8; }

    private static int[] dimensions(Metadata metadata, List<Dimensions> candidates) {
        for (Dimensions d : candidates) {
            for (Directory dir : metadata.getDirectoriesOfType(d.type())) {
                Integer w = dir.getInteger(d.widthTag()), h = dir.getInteger(d.heightTag());
                if (w != null && h != null && w > 0 && h > 0) return new int[]{w, h};
            }
        }
        return null;
    }

    private static int orientation(Metadata metadata) {
        for (ExifIFD0Directory dir : metadata.getDirectoriesOfType(ExifIFD0Directory.class)) {
            Integer o = dir.getInteger(ExifDirectoryBase.TAG_ORIENTATION);
            if (o != null && o >= 1 && o <= 8) return o;
        }
        // HEIF without EXIF: irot holds anticlockwise quarter turns, which map onto EXIF rotations.
        for (HeifDirectory dir : metadata.getDirectoriesOfType(HeifDirectory.class)) {
            Integer turns = dir.getInteger(HeifDirectory.TAG_IMAGE_ROTATION);
            if (turns != null) return switch (turns) { case 1 -> 8; case 2 -> 3; case 3 -> 6; default -> 1; };
        }
        return 1;
    }

    private static boolean quarterTurnVideo(Metadata metadata) {
        for (Directory dir : metadata.getDirectories()) {
            Double degrees = dir instanceof Mp4Directory ? dir.getDoubleObject(Mp4Directory.TAG_ROTATION)
                    : dir instanceof QuickTimeDirectory ? dir.getDoubleObject(QuickTimeDirectory.TAG_ROTATION) : null;
            if (degrees != null) return Math.round(Math.abs(degrees)) % 180 == 90;
        }
        return false;
    }

    private static Instant takenAt(Metadata metadata) {
        for (ExifSubIFDDirectory dir : metadata.getDirectoriesOfType(ExifSubIFDDirectory.class)) {
            // Uses OffsetTimeOriginal when present; otherwise the camera's local time is taken as UTC.
            Instant t = plausible(dir.getDateOriginal(TimeZone.getTimeZone("UTC")));
            if (t != null) return t;
        }
        for (Directory dir : metadata.getDirectories()) {
            Date created = dir instanceof Mp4Directory ? dir.getDate(Mp4Directory.TAG_CREATION_TIME)
                    : dir instanceof QuickTimeDirectory ? dir.getDate(QuickTimeDirectory.TAG_CREATION_TIME) : null;
            Instant t = plausible(created);
            if (t != null) return t;
        }
        return null;
    }

    private static Instant plausible(Date date) {
        return date == null || date.toInstant().isBefore(PLAUSIBLE_AFTER) ? null : date.toInstant();
    }
}
