package net.samitkumar.photo_album_aws.upload;

import net.samitkumar.photo_album_aws.S3StorageProperties;
import net.samitkumar.photo_album_aws.media.MediaProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.Map;
import java.util.UUID;

/**
 * Presigned S3 PUT URLs. Content-Type and Content-Length are part of the signature, so S3 rejects (403) any upload
 * of a different type or size. The URL carries the signer's identity: the API's role needs {@code s3:PutObject}.
 */
@Component
@ConditionalOnProperty(prefix = "spring.application.storage", name = "mode", havingValue = "s3")
class S3UploadUrlSigner implements UploadUrlSigner {
    private final S3Presigner presigner;
    private final S3StorageProperties storage;
    private final MediaProperties media;

    S3UploadUrlSigner(S3Presigner presigner, S3StorageProperties storage, MediaProperties media) {
        this.presigner = presigner;
        this.storage = storage;
        this.media = media;
    }

    @Override
    public PresignedUpload presignPut(UUID albumId, UUID photoId, String objectKey, String contentType, long size) {
        var presigned = presigner.presignPutObject(r -> r.signatureDuration(media.uploadUrlTtl())
                .putObjectRequest(p -> p.bucket(storage.bucket()).key(objectKey).contentType(contentType).contentLength(size)));
        return new PresignedUpload(presigned.url().toString(), Map.of("Content-Type", contentType), presigned.expiration());
    }
}
