package net.samitkumar.photo_album_aws.dynamodb;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** The single DynamoDB table holding albums, photos and share links. */
@Validated
@ConfigurationProperties(prefix = "spring.application.data.dynamodb")
public record DynamoDbDataProperties(@NotBlank String table) {}
