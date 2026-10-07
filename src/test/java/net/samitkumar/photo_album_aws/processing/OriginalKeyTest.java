package net.samitkumar.photo_album_aws.processing;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OriginalKeyTest {
    static final UUID ALBUM = UUID.fromString("0b8f6a8e-3c4d-4e5f-8a9b-0c1d2e3f4a5b");
    static final UUID PHOTO = UUID.fromString("7d6c5b4a-3928-4716-a5b4-c3d2e1f0a9b8");

    @Test
    void parsesKeysWithAndWithoutPrefix() {
        assertEquals(Optional.of(new OriginalKey(ALBUM, PHOTO, "v1")), OriginalKey.parse("originals/" + ALBUM + "/" + PHOTO + "/v1.jpg"));
        assertEquals(Optional.of(new OriginalKey(ALBUM, PHOTO, "v12")), OriginalKey.parse("photo-album/originals/" + ALBUM + "/" + PHOTO + "/v12.heic"));
        assertEquals(Optional.of(new OriginalKey(ALBUM, PHOTO, "v3")), OriginalKey.parse("a/b/originals/" + ALBUM + "/" + PHOTO + "/v3"));
        assertEquals(Optional.of(new OriginalKey(ALBUM, PHOTO, null)), OriginalKey.parse("originals/" + ALBUM + "/" + PHOTO + "/upload.jpg"));
    }

    @Test
    void rejectsOtherKeys() {
        assertTrue(OriginalKey.parse("derived/" + ALBUM + "/" + PHOTO + "/v1/thumb.webp").isEmpty());
        assertTrue(OriginalKey.parse("photo-album/" + ALBUM + "/" + PHOTO).isEmpty());
        assertTrue(OriginalKey.parse("myoriginals/" + ALBUM + "/" + PHOTO + "/v1.jpg").isEmpty());
        assertTrue(OriginalKey.parse("originals/not-a-uuid-not-a-uuid-not-a-uuid-xxxx/" + PHOTO + "/v1.jpg").isEmpty());
        assertTrue(OriginalKey.parse("originals/" + ALBUM + "/" + PHOTO + "/").isEmpty());
        assertTrue(OriginalKey.parse("originals/" + ALBUM + "/" + PHOTO + "/extra/v1.jpg").isEmpty());
        assertTrue(OriginalKey.parse(null).isEmpty());
    }

    @Test
    void derivativesAreScopedToTheOriginalsVersion() {
        assertEquals("derived/" + ALBUM + "/" + PHOTO + "/v2/",
                PhotoProcessor.derivedPrefix(ALBUM, PHOTO, "photo-album/originals/" + ALBUM + "/" + PHOTO + "/v2.png"));
        assertEquals("derived/" + ALBUM + "/" + PHOTO + "/",
                PhotoProcessor.derivedPrefix(ALBUM, PHOTO, "photo-album/" + ALBUM + "/" + PHOTO));
    }
}
