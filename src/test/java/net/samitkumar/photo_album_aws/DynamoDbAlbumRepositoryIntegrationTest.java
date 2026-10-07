package net.samitkumar.photo_album_aws;

import net.samitkumar.photo_album_aws.dynamodb.DynamoDbAlbumRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The repository contract against real DynamoDB APIs (LocalStack), using the Spring-wired repository. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("localstack")
class DynamoDbAlbumRepositoryIntegrationTest extends AlbumRepositoryContract {

    @Autowired
    AlbumRepository repository;

    @Autowired
    DynamoDbClient client;

    @Autowired
    DynamoDbEnhancedClient enhanced;

    @Override
    protected AlbumRepository repository() { return repository; }

    @Test
    void springWiresTheDynamoDbRepository() {
        assertInstanceOf(DynamoDbAlbumRepository.class, repository);
    }

    @Test
    void dataSurvivesANewRepositoryInstanceLikeARestart() {
        var album = album("Persistent", Instant.now());
        repository.create(album);
        repository.addPhoto(album.id(), photo(Instant.now()));

        var afterRestart = new DynamoDbAlbumRepository(client, enhanced, TestcontainersConfiguration.TABLE);
        var found = afterRestart.findById(album.id()).orElseThrow();
        assertEquals("Persistent", found.name());
        assertEquals(1, found.photos().size());
    }

    @Test
    void uploadingPhotosExpireThroughTtlUntilTheUploadCompletes() {
        var album = album("Pending", Instant.now());
        repository.create(album);
        var uploading = photo(Instant.parse("2030-01-01T00:00:00Z")).withStatus(AlbumController.PhotoStatus.UPLOADING);
        repository.addPhoto(album.id(), uploading);
        var key = Map.of("pk", AttributeValue.fromS("ALBUM#" + album.id()), "sk", AttributeValue.fromS("PHOTO#" + uploading.id()));

        var pending = client.getItem(GetItemRequest.builder().tableName(TestcontainersConfiguration.TABLE).key(key).build()).item();
        assertEquals(String.valueOf(uploading.uploadExpiresAt().getEpochSecond()), pending.get("ttl").n());

        repository.updatePhotoStatus(album.id(), uploading.id(), AlbumController.PhotoStatus.UPLOADING, AlbumController.PhotoStatus.PROCESSING);
        var completed = client.getItem(GetItemRequest.builder().tableName(TestcontainersConfiguration.TABLE).key(key).build()).item();
        assertFalse(completed.containsKey("ttl"));
        assertEquals("PROCESSING", completed.get("status").s());
    }

    @Test
    void shareLinksCarryATtlAttributeForAutomaticExpiry() {
        var expires = Instant.parse("2030-01-01T00:00:00Z");
        repository.saveShare("ttl-check", new AlbumController.Share(java.util.UUID.randomUUID(), expires));

        var item = client.getItem(GetItemRequest.builder().tableName(TestcontainersConfiguration.TABLE)
                .key(Map.of("pk", AttributeValue.fromS("SHARE#ttl-check"), "sk", AttributeValue.fromS("META"))).build()).item();
        assertEquals(String.valueOf(expires.getEpochSecond()), item.get("ttl").n());
    }
}
