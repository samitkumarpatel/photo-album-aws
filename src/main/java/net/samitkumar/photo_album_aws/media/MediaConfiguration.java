package net.samitkumar.photo_album_aws.media;

import net.samitkumar.photo_album_aws.S3StorageProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.Optional;

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
                                  ObjectProvider<S3Presigner> presigner, ObjectProvider<S3StorageProperties> s3,
                                  @Value("${PHOTO_ALBUM_CDN_DOMAIN:}") String cdnDomain,
                                  @Value("${PHOTO_ALBUM_CDN_KEY_PAIR_ID:}") String keyPairId,
                                  @Value("${PHOTO_ALBUM_CDN_SIGNING_KMS_KEY_ARN:}") String signingKeyArn,
                                  ObjectProvider<KmsClient> kms) {
        if (!"s3".equals(storageMode)) return new ApiMediaUrlSigner();
        if (!cdnDomain.isBlank() && !keyPairId.isBlank() && !signingKeyArn.isBlank()) {
            return new CloudFrontMediaUrlSigner(cdnDomain, keyPairId, kms.getObject(), signingKeyArn, media.urlTtl());
        }
        return new S3MediaUrlSigner(presigner.getObject(), s3.getObject().bucket(), media.urlTtl());
    }

    @Bean(destroyMethod = "close")
    KmsClient mediaSigningKmsClient() {
        var region = Optional.ofNullable(System.getenv("AWS_REGION")).filter(s -> !s.isBlank()).orElse("eu-north-1");
        return KmsClient.builder().region(Region.of(region)).build();
    }
}
