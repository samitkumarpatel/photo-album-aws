package net.samitkumar.photo_album_aws.media;

import net.samitkumar.photo_album_aws.controller.AlbumController.Photo;

import java.util.Locale;

/** The renditions a client can ask for. Missing derivatives fall back to the next larger rendition. */
public enum MediaSize {
    THUMBNAIL, DISPLAY, ORIGINAL;

    /** Storage key that serves this rendition right now. */
    public String key(Photo photo) {
        return switch (this) {
            case THUMBNAIL -> photo.thumbnailKey() != null ? photo.thumbnailKey() : DISPLAY.key(photo);
            case DISPLAY -> photo.displayKey() != null ? photo.displayKey() : photo.objectKey();
            case ORIGINAL -> photo.objectKey();
        };
    }

    public String param() { return name().toLowerCase(Locale.ROOT); }

    /** Parses the {@code size} query parameter; null means the original. */
    public static MediaSize parse(String value) {
        if (value == null || value.isBlank()) return ORIGINAL;
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("size must be thumbnail, display or original"); }
    }
}
