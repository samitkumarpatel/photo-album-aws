package net.samitkumar.photo_album_aws.processing;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import net.samitkumar.photo_album_aws.controller.AlbumController.Album;
import net.samitkumar.photo_album_aws.controller.AlbumController.Photo;
import net.samitkumar.photo_album_aws.controller.AlbumController.PhotoStatus;
import net.samitkumar.photo_album_aws.repository.InMemoryAlbumRepository;
import net.samitkumar.photo_album_aws.InMemoryMediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class S3EventWorkerHandlerTest {
    static final String TEST_EVENT = """
            {"Service":"Amazon S3","Event":"s3:TestEvent","Time":"2026-10-06T10:00:00.000Z","Bucket":"media","RequestId":"R","HostId":"H"}""";

    InMemoryAlbumRepository repository;
    InMemoryMediaStorage storage;
    S3EventWorkerHandler handler;
    UUID albumId;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAlbumRepository();
        storage = new InMemoryMediaStorage();
        handler = new S3EventWorkerHandler(new PhotoProcessor(repository, storage, PosterExtractor.none()));
        albumId = UUID.randomUUID();
        repository.create(new Album(albumId, "Trip", "", Instant.now(), List.of()));
    }

    @Test
    void readsUrlEncodedKeysOfCreatedObjects() {
        assertEquals(List.of("photo-album/originals/a/b/v1 copy+1.jpg"),
                S3EventWorkerHandler.objectKeys(s3Event("ObjectCreated:Put", "photo-album/originals/a/b/v1+copy%2B1.jpg")));
        assertEquals(List.of(), S3EventWorkerHandler.objectKeys(s3Event("ObjectRemoved:Delete", "originals/a/b/v1.jpg")));
        assertEquals(List.of(), S3EventWorkerHandler.objectKeys(TEST_EVENT));
    }

    @Test
    void unwrapsSnsEnvelopes() {
        String inner = s3Event("ObjectCreated:CompleteMultipartUpload", "originals/a/b/v2.png");
        String sns = "{\"Type\":\"Notification\",\"MessageId\":\"m\",\"Message\":" + quote(inner) + "}";
        assertEquals(List.of("originals/a/b/v2.png"), S3EventWorkerHandler.objectKeys(sns));
    }

    @Test
    void processesOriginalsAndAcknowledgesEverythingElse() throws IOException {
        Photo photo = upload(TestMedia.jpeg(120, 80));

        SQSBatchResponse response = handler.handleRequest(event(
                message("1", s3Event("ObjectCreated:Put", photo.objectKey())),
                message("2", TEST_EVENT),
                message("3", s3Event("ObjectCreated:Put", "derived/" + albumId + "/" + photo.id() + "/v1/thumb.webp")),
                message("4", s3Event("ObjectCreated:Put", "originals/" + albumId + "/" + UUID.randomUUID() + "/v1.jpg"))), null);

        assertEquals(List.of(), response.getBatchItemFailures());
        Photo done = repository.findById(albumId).orElseThrow().photos().getFirst();
        assertEquals(PhotoStatus.READY, done.status());
        assertEquals(120, done.width());
        assertNotNull(done.thumbnailKey());
    }

    @Test
    void reportsOnlyTheMessagesThatFailed() throws IOException {
        Photo photo = upload(TestMedia.jpeg(10, 10));
        Photo missing = upload(TestMedia.jpeg(10, 10));
        storage.delete(missing.objectKey());

        SQSBatchResponse response = handler.handleRequest(event(
                message("ok", s3Event("ObjectCreated:Put", photo.objectKey())),
                message("gone", s3Event("ObjectCreated:Put", missing.objectKey())),
                message("garbage", "not json")), null);

        assertEquals(List.of("gone", "garbage"), response.getBatchItemFailures().stream().map(SQSBatchResponse.BatchItemFailure::getItemIdentifier).toList());
        assertEquals(PhotoStatus.READY, repository.findById(albumId).orElseThrow().photos().stream()
                .filter(p -> p.id().equals(photo.id())).findFirst().orElseThrow().status());
    }

    private Photo upload(byte[] content) throws IOException {
        UUID photoId = UUID.randomUUID();
        String key = storage.putObject("originals/" + albumId + "/" + photoId + "/v1.jpg", "image/jpeg", content.length, new ByteArrayInputStream(content));
        var photo = new Photo(photoId, "a.jpg", "image/jpeg", content.length, key, Instant.now(), null,
                PhotoStatus.PROCESSING, null, null, null, null, null);
        repository.addPhoto(albumId, photo);
        return photo;
    }

    static String s3Event(String eventName, String encodedKey) {
        return """
                {"Records":[{"eventVersion":"2.1","eventSource":"aws:s3","awsRegion":"eu-north-1","eventTime":"2026-10-06T10:00:00.000Z",
                "eventName":"%s","s3":{"s3SchemaVersion":"1.0","configurationId":"originals",
                "bucket":{"name":"media","arn":"arn:aws:s3:::media"},"object":{"key":"%s","size":1234,"sequencer":"0A1B"}}}]}"""
                .formatted(eventName, encodedKey);
    }

    static SQSEvent.SQSMessage message(String id, String body) {
        var message = new SQSEvent.SQSMessage();
        message.setMessageId(id);
        message.setBody(body);
        return message;
    }

    static SQSEvent event(SQSEvent.SQSMessage... messages) {
        var event = new SQSEvent();
        event.setRecords(List.of(messages));
        return event;
    }

    private static String quote(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
