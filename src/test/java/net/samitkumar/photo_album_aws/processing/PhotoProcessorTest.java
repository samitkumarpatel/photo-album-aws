package net.samitkumar.photo_album_aws.processing;

import net.samitkumar.photo_album_aws.AlbumController.Album;
import net.samitkumar.photo_album_aws.AlbumController.Photo;
import net.samitkumar.photo_album_aws.AlbumController.PhotoStatus;
import net.samitkumar.photo_album_aws.InMemoryAlbumRepository;
import net.samitkumar.photo_album_aws.InMemoryMediaStorage;
import net.samitkumar.photo_album_aws.processing.PhotoProcessor.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PhotoProcessorTest {
    InMemoryAlbumRepository repository;
    InMemoryMediaStorage storage;
    PhotoProcessor processor;
    UUID albumId;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAlbumRepository();
        storage = new InMemoryMediaStorage();
        processor = new PhotoProcessor(repository, storage, PosterExtractor.none());
        albumId = UUID.randomUUID();
        repository.create(new Album(albumId, "Trip", "", Instant.now(), List.of()));
    }

    @Test
    void writesWebpDerivativesWithoutUpscaling() throws IOException {
        Photo photo = upload("image/jpeg", TestMedia.jpeg(800, 600));

        assertEquals(Outcome.READY, processor.process(albumId, photo.id()));

        Photo done = reload(photo);
        assertEquals(PhotoStatus.READY, done.status());
        assertEquals(800, done.width());
        assertEquals(600, done.height());
        assertNull(done.takenAt());
        assertEquals("derived/" + albumId + "/" + photo.id() + "/v1/display.webp", done.displayKey());
        assertEquals("derived/" + albumId + "/" + photo.id() + "/v1/thumb.webp", done.thumbnailKey());
        assertSize(800, 600, done.displayKey());
        assertSize(400, 300, done.thumbnailKey());
        assertArrayEquals(new byte[]{'R', 'I', 'F', 'F'}, java.util.Arrays.copyOf(bytes(done.thumbnailKey()), 4));
    }

    @Test
    void downscalesLargeImagesToTheDisplayAndThumbnailEdges() throws IOException {
        Photo photo = upload("image/png", TestMedia.png(3000, 1000, true));

        assertEquals(Outcome.READY, processor.process(albumId, photo.id()));

        Photo done = reload(photo);
        assertEquals(3000, done.width());
        assertEquals(1000, done.height());
        assertSize(2048, 683, done.displayKey());
        assertSize(400, 133, done.thumbnailKey());
        // Smooth scaling keeps the red quarter red and the edges of the blocks intact.
        BufferedImage display = read(done.displayKey());
        assertDominant(Color.RED, display.getRGB(100, 50));
        assertDominant(Color.BLUE, display.getRGB(2000, 600));
    }

    @Test
    void appliesExifOrientationToDimensionsAndPixels() throws IOException {
        byte[] jpeg = TestMedia.withExif(TestMedia.jpeg(800, 600), 6, "2024:05:06 07:08:09", null);
        Photo photo = upload("image/jpeg", jpeg);

        assertEquals(Outcome.READY, processor.process(albumId, photo.id()));

        Photo done = reload(photo);
        assertEquals(600, done.width());
        assertEquals(800, done.height());
        assertEquals(Instant.parse("2024-05-06T07:08:09Z"), done.takenAt(), "no offset recorded, so UTC is assumed");
        BufferedImage display = read(done.displayKey());
        assertEquals(600, display.getWidth());
        assertEquals(800, display.getHeight());
        // Rotating 90 degrees clockwise moves the stored top-left red block to the top right.
        assertDominant(Color.RED, display.getRGB(450, 100));
        assertDominant(Color.BLUE, display.getRGB(100, 100));
        assertSize(300, 400, done.thumbnailKey());
    }

    @Test
    void usesTheRecordedTimeOffset() throws IOException {
        byte[] jpeg = TestMedia.withExif(TestMedia.jpeg(64, 48), 1, "2024:05:06 07:08:09", "+02:00");
        Photo photo = upload("image/jpeg", jpeg);

        processor.process(albumId, photo.id());

        assertEquals(Instant.parse("2024-05-06T05:08:09Z"), reload(photo).takenAt());
    }

    @Test
    void failsWhenContentDoesNotMatchTheDeclaredType() throws IOException {
        Photo png = upload("image/jpeg", TestMedia.png(10, 10, false));
        Photo html = upload("image/png", "<html><script>alert(1)</script></html>".getBytes());

        assertEquals(Outcome.FAILED, processor.process(albumId, png.id()));
        assertEquals(Outcome.FAILED, processor.process(albumId, html.id()));

        assertEquals(PhotoStatus.FAILED, reload(png).status());
        assertEquals(PhotoStatus.FAILED, reload(html).status());
        assertNull(reload(png).thumbnailKey());
        assertNotNull(bytes(png.objectKey()), "the original is kept");
    }

    @Test
    void keepsValidButUndecodableFormatsWithoutDerivatives() throws IOException {
        Photo heic = upload("image/heic", TestMedia.isoBmff("heic", "mif1", "heic"));
        Photo video = upload("video/mp4", TestMedia.isoBmff("isom", "isom", "mp41"));

        assertEquals(Outcome.READY_WITHOUT_DERIVATIVES, processor.process(albumId, heic.id()));
        assertEquals(Outcome.READY_WITHOUT_DERIVATIVES, processor.process(albumId, video.id()));

        assertEquals(PhotoStatus.READY, reload(heic).status());
        assertNull(reload(heic).thumbnailKey());
        assertNull(reload(video).displayKey());
    }

    @Test
    void refusesToDecodeHugeImagesButKeepsTheirDimensions() throws IOException {
        Photo photo = upload("image/png", TestMedia.pngHeaderOnly(20_000, 20_000));

        assertEquals(Outcome.READY_WITHOUT_DERIVATIVES, processor.process(albumId, photo.id()));

        Photo done = reload(photo);
        assertEquals(20_000, done.width());
        assertNull(done.thumbnailKey());
    }

    @Test
    void videoPostersBecomeThumbnails() throws IOException {
        processor = new PhotoProcessor(repository, storage, (video, type) -> Optional.of(TestMedia.marked(1920, 1080, false)));
        Photo video = upload("video/quicktime", TestMedia.isoBmff("qt  ", "qt  "));

        assertEquals(Outcome.READY, processor.process(albumId, video.id()));

        assertSize(400, 225, reload(video).thumbnailKey());
    }

    @Test
    void isIdempotentAndIgnoresStaleEvents() throws IOException {
        Photo photo = upload("image/png", TestMedia.png(50, 50, false));
        assertEquals(Outcome.READY, processor.process(albumId, photo.id(), photo.objectKey()));

        assertEquals(Outcome.SKIPPED, processor.process(albumId, photo.id(), photo.objectKey()));
        assertEquals(Outcome.SKIPPED, processor.process(albumId, photo.id(), "originals/" + albumId + "/" + photo.id() + "/v0.png"));
        assertEquals(Outcome.SKIPPED, processor.process(albumId, UUID.randomUUID()));
        assertEquals(Outcome.SKIPPED, processor.process(UUID.randomUUID(), photo.id()));
    }

    @Test
    void newVersionReplacesThePreviousDerivatives() throws IOException {
        Photo photo = upload("image/png", TestMedia.png(50, 50, false));
        processor.process(albumId, photo.id());
        Photo v1 = reload(photo);

        String v2Key = storage.putObject("originals/" + albumId + "/" + photo.id() + "/v2.jpg", "image/jpeg", 0, new ByteArrayInputStream(TestMedia.jpeg(60, 40)));
        Photo v2 = new Photo(photo.id(), "edited.jpg", "image/jpeg", 0, v2Key, photo.uploadedAt(), Instant.now(),
                PhotoStatus.PROCESSING, v1.width(), v1.height(), null, v1.thumbnailKey(), v1.displayKey());
        repository.replacePhoto(albumId, v2);

        assertEquals(Outcome.READY, processor.process(albumId, photo.id()));

        Photo done = reload(photo);
        assertEquals(60, done.width());
        assertTrue(done.thumbnailKey().contains("/v2/"));
        assertThrows(IOException.class, () -> storage.open(v1.thumbnailKey()));
        assertThrows(IOException.class, () -> storage.open(v1.displayKey()));
    }

    @Test
    void anEditLandingDuringTheSaveWinsAndOrphanDerivativesAreRemoved() throws IOException {
        var v2Key = new String[1];
        // Simulates an edit written after the processor's own check but before its save.
        repository = new InMemoryAlbumRepository() {
            @Override
            public boolean replacePhotoIfCurrent(UUID album, Photo photo, String expectedObjectKey) {
                if (v2Key[0] == null) {
                    v2Key[0] = "originals/" + album + "/" + photo.id() + "/v2.jpg";
                    replacePhoto(album, new Photo(photo.id(), "edited.jpg", "image/jpeg", 1, v2Key[0], photo.uploadedAt(), Instant.now(),
                            PhotoStatus.PROCESSING, null, null, null, null, null));
                }
                return super.replacePhotoIfCurrent(album, photo, expectedObjectKey);
            }
        };
        repository.create(new Album(albumId, "Trip", "", Instant.now(), List.of()));
        processor = new PhotoProcessor(repository, storage, PosterExtractor.none());
        Photo photo = upload("image/png", TestMedia.png(50, 50, false));

        assertEquals(Outcome.SKIPPED, processor.process(albumId, photo.id()));

        Photo current = reload(photo);
        assertEquals(v2Key[0], current.objectKey());
        assertEquals(PhotoStatus.PROCESSING, current.status());
        assertTrue(storage.size("derived/" + albumId + "/" + photo.id() + "/v1/thumb.webp").isEmpty());
        assertTrue(storage.size("derived/" + albumId + "/" + photo.id() + "/v1/display.webp").isEmpty());
    }

    @Test
    void markFailedOnlyTouchesTheSameOriginal() throws IOException {
        Photo photo = upload("image/png", TestMedia.png(5, 5, false));

        processor.markFailed(albumId, photo.id(), "originals/other");
        assertEquals(PhotoStatus.PROCESSING, reload(photo).status());

        processor.markFailed(albumId, photo.id(), photo.objectKey());
        assertEquals(PhotoStatus.FAILED, reload(photo).status());
    }

    private Photo upload(String contentType, byte[] content) throws IOException {
        UUID photoId = UUID.randomUUID();
        String ext = contentType.substring(contentType.indexOf('/') + 1);
        String key = storage.putObject("originals/" + albumId + "/" + photoId + "/v1." + ext, contentType, content.length, new ByteArrayInputStream(content));
        var photo = new Photo(photoId, "file." + ext, contentType, content.length, key, Instant.now(), null,
                PhotoStatus.PROCESSING, null, null, null, null, null);
        assertTrue(repository.addPhoto(albumId, photo));
        return photo;
    }

    private Photo reload(Photo photo) {
        return repository.findById(albumId).orElseThrow().photos().stream().filter(p -> p.id().equals(photo.id())).findFirst().orElseThrow();
    }

    private byte[] bytes(String key) throws IOException {
        try (InputStream in = storage.open(key)) { return in.readAllBytes(); }
    }

    private BufferedImage read(String key) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes(key)));
        assertNotNull(image, "derivative " + key + " is decodable");
        return image;
    }

    private void assertSize(int width, int height, String key) throws IOException {
        BufferedImage image = read(key);
        assertEquals(width + "x" + height, image.getWidth() + "x" + image.getHeight(), key);
    }

    private static void assertDominant(Color expected, int argb) {
        var actual = new Color(argb);
        int[] rgb = {actual.getRed(), actual.getGreen(), actual.getBlue()};
        int[] want = {expected.getRed(), expected.getGreen(), expected.getBlue()};
        int strongest = want[0] > 0 ? 0 : want[1] > 0 ? 1 : 2;
        for (int i = 0; i < 3; i++) {
            if (i != strongest) assertTrue(rgb[strongest] > rgb[i] + 60, "expected " + expected + " but was " + actual);
        }
    }
}
