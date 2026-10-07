package net.samitkumar.photo_album_aws.dynamodb;

import net.samitkumar.photo_album_aws.repository.AlbumRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Stores album data in DynamoDB when {@code spring.application.data.mode=dynamodb}, using the clients Spring Cloud AWS
 * auto-configures. The table must already exist; infrastructure as code (or the LocalStack init script) creates it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.application.data", name = "mode", havingValue = "dynamodb")
@EnableConfigurationProperties(DynamoDbDataProperties.class)
class DynamoDbDataConfiguration {

    @Bean
    AlbumRepository albumRepository(DynamoDbClient client, DynamoDbEnhancedClient enhanced, DynamoDbDataProperties properties) {
        return new DynamoDbAlbumRepository(client, enhanced, properties.table());
    }
}
