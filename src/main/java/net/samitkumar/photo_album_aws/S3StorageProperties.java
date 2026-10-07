package net.samitkumar.photo_album_aws;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where media lives in S3. Region, credentials and endpoint are configured through {@code spring.cloud.aws.*}.
 */
@Validated
@ConfigurationProperties(prefix = "spring.application.storage.s3")
public record S3StorageProperties(@NotBlank String bucket, String prefix) {}
