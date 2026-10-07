package net.samitkumar.photo_album_aws.processing;

import net.samitkumar.photo_album_aws.AlbumController;
import net.samitkumar.photo_album_aws.AlbumController.Album;
import net.samitkumar.photo_album_aws.AlbumController.Photo;
import net.samitkumar.photo_album_aws.AlbumController.PhotoStatus;
import net.samitkumar.photo_album_aws.AlbumRepository;
import net.samitkumar.photo_album_aws.MediaStorage;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Boots the worker's own lean context (memory modes) the way the Lambda runtime does, through the no-arg handler. */
class WorkerApplicationTest {

    @Test
    void handlerBootsALeanContextAndProcessesEvents() throws Exception {
        var handler = new S3EventWorkerHandler();
        ConfigurableApplicationContext context = WorkerApplication.context();

        assertEquals(0, context.getBeanNamesForType(ProcessingTrigger.class).length, "the worker must never trigger processing itself");
        assertEquals(0, context.getBeanNamesForType(AlbumController.class).length);
        assertEquals(0, context.getBeanNamesForType(org.springframework.web.servlet.DispatcherServlet.class).length);

        AlbumRepository repository = context.getBean(AlbumRepository.class);
        MediaStorage storage = context.getBean(MediaStorage.class);
        UUID albumId = UUID.randomUUID(), photoId = UUID.randomUUID();
        byte[] png = TestMedia.png(30, 20, false);
        String key = storage.putObject("originals/" + albumId + "/" + photoId + "/v1.png", "image/png", png.length, new ByteArrayInputStream(png));
        repository.create(new Album(albumId, "Worker", "", Instant.now(), List.of()));
        repository.addPhoto(albumId, new Photo(photoId, "a.png", "image/png", png.length, key, Instant.now(), null,
                PhotoStatus.PROCESSING, null, null, null, null, null));

        var response = handler.handleRequest(S3EventWorkerHandlerTest.event(
                S3EventWorkerHandlerTest.message("m", S3EventWorkerHandlerTest.s3Event("ObjectCreated:Put", key))), null);

        assertTrue(response.getBatchItemFailures().isEmpty());
        Photo done = repository.findById(albumId).orElseThrow().photos().getFirst();
        assertEquals(PhotoStatus.READY, done.status());
        assertEquals(30, done.width());
    }
}
