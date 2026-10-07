package net.samitkumar.photo_album_aws.processing;

import net.samitkumar.photo_album_aws.controller.AlbumController.Album;
import net.samitkumar.photo_album_aws.controller.AlbumController.Photo;
import net.samitkumar.photo_album_aws.controller.AlbumController.PhotoStatus;
import net.samitkumar.photo_album_aws.repository.AlbumRepository;
import net.samitkumar.photo_album_aws.MediaStorage;
import net.samitkumar.photo_album_aws.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The full processing path against S3 and DynamoDB in LocalStack: prefixed keys, derivative uploads with their content
 * type, and the processed photo record. Same configuration as the other LocalStack tests, so the context is shared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("localstack")
class PhotoProcessorLocalStackTest {

    @Autowired PhotoProcessor processor;
    @Autowired AlbumRepository repository;
    @Autowired MediaStorage storage;
    @Autowired S3Client s3;
    @Value("${spring.application.storage.s3.bucket}") String bucket;

    @Test
    void processesAnOriginalStoredInS3() throws Exception {
        UUID albumId = UUID.randomUUID(), photoId = UUID.randomUUID();
        byte[] jpeg = TestMedia.withExif(TestMedia.jpeg(1200, 900), 8, "2023:12:24 18:00:00", null);
        String key = storage.putObject("originals/" + albumId + "/" + photoId + "/v1.jpg", "image/jpeg", jpeg.length, new ByteArrayInputStream(jpeg));
        assertTrue(key.startsWith("photo-album/originals/"));
        repository.create(new Album(albumId, "LocalStack", "", Instant.now(), List.of()));
        repository.addPhoto(albumId, new Photo(photoId, "a.jpg", "image/jpeg", jpeg.length, key, Instant.now(), null,
                PhotoStatus.PROCESSING, null, null, null, null, null));

        assertEquals(PhotoProcessor.Outcome.READY, processor.process(albumId, photoId, key));

        Photo done = repository.findById(albumId).orElseThrow().photos().getFirst();
        assertEquals(PhotoStatus.READY, done.status());
        assertEquals(900, done.width());
        assertEquals(1200, done.height());
        assertEquals(Instant.parse("2023-12-24T18:00:00Z"), done.takenAt());
        assertEquals("photo-album/derived/" + albumId + "/" + photoId + "/v1/thumb.webp", done.thumbnailKey());
        var head = s3.headObject(b -> b.bucket(bucket).key(done.displayKey()));
        assertEquals("image/webp", head.contentType());
        assertTrue(head.contentLength() > 0);
    }
}
