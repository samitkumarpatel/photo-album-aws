package net.samitkumar.photo_album_aws;

import io.awspring.cloud.s3.S3Template;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Stores media in S3 using the clients Spring Cloud AWS auto-configures.
 *
 * <p>Region, credentials and endpoint come from the standard {@code spring.cloud.aws.*} properties, so the same
 * code talks to real AWS in production and to LocalStack in tests.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.application.storage", name = "mode", havingValue = "s3")
@EnableConfigurationProperties(S3StorageProperties.class)
class S3MediaStorage {

    @Bean
    MediaStorage mediaStorage(S3Client s3Client, S3Template s3Template, S3StorageProperties properties) {
        return new S3ObjectMediaStorage(s3Client, s3Template, properties);
    }

    static class S3ObjectMediaStorage implements MediaStorage {
        private final S3Client s3;
        private final S3Template template;
        private final S3StorageProperties properties;

        S3ObjectMediaStorage(S3Client s3, S3Template template, S3StorageProperties properties) {
            this.s3 = s3;
            this.template = template;
            this.properties = properties;
        }

        /**
         * Streams straight to S3 with a known length. S3Template#upload would buffer the whole file in memory
         * first, which matters for 100 MB videos.
         */
        @Override
        public String putObject(String relativeKey, String contentType, long size, InputStream content) throws IOException {
            String key = objectKey(relativeKey);
            s3.putObject(PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType(contentType)
                            .contentLength(size)
                            .build(),
                    RequestBody.fromInputStream(content, size));
            return key;
        }

        @Override
        public String copyObject(String sourceObjectKey, String destinationRelativeKey, String contentType) {
            String destinationKey = objectKey(destinationRelativeKey);
            s3.copyObject(r -> r.copySource(properties.bucket() + "/" + sourceObjectKey)
                    .destinationBucket(properties.bucket())
                    .destinationKey(destinationKey)
                    .metadataDirective(MetadataDirective.REPLACE)
                    .contentType(contentType));
            return destinationKey;
        }

        @Override
        public InputStream open(String objectKey) throws IOException {
            return template.download(properties.bucket(), objectKey).getInputStream();
        }

        @Override
        public Optional<Long> size(String objectKey) {
            try {
                return Optional.of(s3.headObject(r -> r.bucket(properties.bucket()).key(objectKey)).contentLength());
            } catch (S3Exception e) {
                // HEAD responses have no body, so a missing key surfaces as a bare 404 rather than NoSuchKeyException.
                if (e.statusCode() == 404) return Optional.empty();
                throw e;
            }
        }

        @Override
        public void delete(String objectKey) {
            template.deleteObject(properties.bucket(), objectKey);
        }

        /** Lists the prefix and deletes in batches of up to 1000 keys, the DeleteObjects limit (one list page). */
        @Override
        public void deletePrefix(String objectKeyPrefix) {
            for (var page : s3.listObjectsV2Paginator(r -> r.bucket(properties.bucket()).prefix(objectKeyPrefix))) {
                List<ObjectIdentifier> keys = page.contents().stream().map(o -> ObjectIdentifier.builder().key(o.key()).build()).toList();
                if (keys.isEmpty()) continue;
                var result = s3.deleteObjects(r -> r.bucket(properties.bucket()).delete(Delete.builder().objects(keys).quiet(true).build()));
                if (result.hasErrors() && !result.errors().isEmpty()) throw new IllegalStateException("Could not delete " + result.errors());
            }
        }

        @Override
        public String objectKey(String relativeKey) {
            String prefix = properties.prefix() == null ? "" : properties.prefix().trim().replaceAll("^/+|/+$", "");
            return prefix.isEmpty() ? relativeKey : prefix + "/" + relativeKey;
        }
    }
}
