package net.samitkumar.photo_album_aws.processing;

import net.samitkumar.photo_album_aws.controller.AlbumController.Album;
import net.samitkumar.photo_album_aws.controller.AlbumController.Photo;
import net.samitkumar.photo_album_aws.controller.AlbumController.PhotoStatus;
import net.samitkumar.photo_album_aws.repository.InMemoryAlbumRepository;
import net.samitkumar.photo_album_aws.InMemoryMediaStorage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InProcessProcessingTriggerTest {
    final InMemoryAlbumRepository repository = new InMemoryAlbumRepository();
    final InMemoryMediaStorage storage = new InMemoryMediaStorage();
    final UUID albumId = UUID.randomUUID();

    @Test
    void processesInTheBackgroundAndMarksFailuresFailed() throws Exception {
        repository.create(new Album(albumId, "Local", "", Instant.now(), List.of()));
        Photo good = photo(TestMedia.png(20, 20, false));
        Photo lost = photo(TestMedia.png(20, 20, false));
        storage.delete(lost.objectKey());

        var trigger = new InProcessProcessingTrigger(new PhotoProcessor(repository, storage, PosterExtractor.none()));
        trigger.uploaded(albumId, good.id());
        trigger.uploaded(albumId, lost.id());
        trigger.destroy(); // waits for queued work

        assertEquals(PhotoStatus.READY, status(good));
        assertEquals(PhotoStatus.FAILED, status(lost));
    }

    private Photo photo(byte[] png) throws Exception {
        UUID id = UUID.randomUUID();
        String key = storage.putObject("originals/" + albumId + "/" + id + "/v1.png", "image/png", png.length, new ByteArrayInputStream(png));
        var photo = new Photo(id, "a.png", "image/png", png.length, key, Instant.now(), null, PhotoStatus.PROCESSING, null, null, null, null, null);
        repository.addPhoto(albumId, photo);
        return photo;
    }

    private PhotoStatus status(Photo photo) {
        return repository.findById(albumId).orElseThrow().photos().stream().filter(p -> p.id().equals(photo.id())).findFirst().orElseThrow().status();
    }
}
