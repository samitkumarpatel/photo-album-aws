package net.samitkumar.photo_album_aws.processing;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An original's object key, {@code [prefix/]originals/{albumId}/{photoId}/v{n}.{ext}}. The prefix is whatever the
 * storage is configured with, so parsing looks for the {@code originals/} segment rather than the start of the key.
 */
record OriginalKey(UUID albumId, UUID photoId, String version) {
    private static final Pattern KEY = Pattern.compile(
            "(?:^|/)originals/([0-9a-fA-F-]{36})/([0-9a-fA-F-]{36})/([^/]+)$");
    private static final Pattern VERSION = Pattern.compile("^(v\\d+)(?:\\.[^/.]*)?$");

    static Optional<OriginalKey> parse(String objectKey) {
        if (objectKey == null) return Optional.empty();
        Matcher m = KEY.matcher(objectKey);
        if (!m.find()) return Optional.empty();
        try {
            Matcher v = VERSION.matcher(m.group(3));
            return Optional.of(new OriginalKey(UUID.fromString(m.group(1)), UUID.fromString(m.group(2)), v.matches() ? v.group(1) : null));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
