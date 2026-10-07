package net.samitkumar.photo_album_aws.processing;

import net.samitkumar.photo_album_aws.processing.MediaSniffer.DetectedMedia;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static net.samitkumar.photo_album_aws.processing.MediaSniffer.detect;
import static org.junit.jupiter.api.Assertions.*;

class MediaSnifferTest {

    @Test
    void detectsImagesWrittenByImageIO() throws IOException {
        assertEquals(Optional.of(DetectedMedia.JPEG), detect(TestMedia.jpeg(8, 8)));
        assertEquals(Optional.of(DetectedMedia.PNG), detect(TestMedia.png(8, 8, false)));
        assertEquals(Optional.of(DetectedMedia.GIF), detect(TestMedia.write(TestMedia.marked(8, 8, false), "gif")));
        assertEquals(Optional.of(DetectedMedia.BMP), detect(TestMedia.write(TestMedia.marked(8, 8, false), "bmp")));
        assertEquals(Optional.of(DetectedMedia.TIFF), detect(TestMedia.write(TestMedia.marked(8, 8, false), "tiff")));
    }

    @Test
    void detectsRiffAndEbmlContainers() {
        assertEquals(Optional.of(DetectedMedia.WEBP), detect(bytes("RIFF\0\0\0\0WEBPVP8 ")));
        assertEquals(Optional.of(DetectedMedia.AVI), detect(bytes("RIFF\0\0\0\0AVI LIST")));
        byte[] ebml = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x9F, 0x42, (byte) 0x82, (byte) 0x84};
        assertEquals(Optional.of(DetectedMedia.WEBM), detect(TestMedia.concat(ebml, bytes("webm"))));
        assertEquals(Optional.of(DetectedMedia.MKV), detect(TestMedia.concat(ebml, bytes("matroska"))));
    }

    @Test
    void readsIsoBmffBrands() {
        assertEquals(Optional.of(DetectedMedia.HEIC), detect(TestMedia.isoBmff("heic", "mif1", "heic")));
        assertEquals(Optional.of(DetectedMedia.HEIC), detect(TestMedia.isoBmff("mif1", "mif1", "heic")));
        assertEquals(Optional.of(DetectedMedia.HEIF), detect(TestMedia.isoBmff("mif1", "mif1")));
        assertEquals(Optional.of(DetectedMedia.AVIF), detect(TestMedia.isoBmff("avif", "mif1", "miaf")));
        assertEquals(Optional.of(DetectedMedia.MOV), detect(TestMedia.isoBmff("qt  ", "qt  ")));
        assertEquals(Optional.of(DetectedMedia.MP4), detect(TestMedia.isoBmff("isom", "isom", "iso2", "mp41")));
        assertEquals(Optional.of(DetectedMedia.MOV), detect(bytes("\0\0\0\u0008wide\0\0\0\0mdat")));
    }

    @Test
    void rejectsContentThatIsNotMedia() {
        assertEquals(Optional.empty(), detect(bytes("<!doctype html><script>alert(1)</script>")));
        assertEquals(Optional.empty(), detect(bytes("%PDF-1.7")));
        assertEquals(Optional.empty(), detect(new byte[]{'M', 'Z', (byte) 0x90, 0}));
        assertEquals(Optional.empty(), detect(bytes("BM but not a bitmap")));
        assertEquals(Optional.empty(), detect(new byte[0]));
    }

    @Test
    void matchesDeclaredContentTypeLeniently() {
        assertTrue(DetectedMedia.JPEG.matches("image/jpeg"));
        assertTrue(DetectedMedia.JPEG.matches("IMAGE/JPG; charset=binary"));
        assertTrue(DetectedMedia.MOV.matches("video/mp4"));
        assertTrue(DetectedMedia.MP4.matches("video/quicktime"));
        assertTrue(DetectedMedia.WEBM.matches("video/x-matroska"));
        assertTrue(DetectedMedia.HEIF.matches("image/avif"));
        assertFalse(DetectedMedia.HEIC.matches("image/avif"));
        assertFalse(DetectedMedia.PNG.matches("image/jpeg"));
        assertFalse(DetectedMedia.MP4.matches("image/heic"));
        assertFalse(DetectedMedia.JPEG.matches(null));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
