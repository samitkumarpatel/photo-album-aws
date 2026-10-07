package net.samitkumar.photo_album_aws.media;

import net.samitkumar.photo_album_aws.S3StorageProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Picks how media URLs are issued: API routes with in-memory storage, otherwise S3 presigned URLs. The S3Presigner is
 * the one Spring Cloud AWS auto-configures, so it shares region, credentials, endpoint and path-style settings with
 * the S3 client.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaProperties.class)
class MediaConfiguration {

    @Bean
    MediaUrlSigner mediaUrlSigner(MediaProperties media, @Value("${spring.application.storage.mode:memory}") String storageMode,
                                  ObjectProvider<S3Presigner> presigner, ObjectProvider<S3StorageProperties> s3) {
        if (!"s3".equals(storageMode)) return new ApiMediaUrlSigner();
        return new S3MediaUrlSigner(presigner.getObject(), s3.getObject().bucket(), media.urlTtl());
    }
}
