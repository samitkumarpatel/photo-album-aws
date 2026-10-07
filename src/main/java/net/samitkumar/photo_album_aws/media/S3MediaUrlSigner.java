package net.samitkumar.photo_album_aws.media;

import net.samitkumar.photo_album_aws.AlbumController.Photo;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.time.Instant;

/**
 * Presigned S3 GET URLs, for S3 storage without a CloudFront distribution. A URL signed with temporary credentials
 * (a Lambda or ECS role) stops working when those credentials expire, even if its own expiry is later.
 */
public class S3MediaUrlSigner implements MediaUrlSigner {
    private static final Duration MIN = Duration.ofSeconds(1);
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration ttl;

    public S3MediaUrlSigner(S3Presigner presigner, String bucket, Duration ttl) {
        this.presigner = presigner;
        this.bucket = bucket;
        this.ttl = ttl;
    }

    @Override
    public String url(Photo photo, MediaSize size, String objectKey, String apiPath, Instant notAfter) {
        return presign(objectKey, null, notAfter);
    }

    /** S3 applies {@code response-content-disposition} because it is part of the signed request. */
    @Override
    public String downloadUrl(Photo photo, String apiPath, Instant notAfter) {
        return presign(photo.objectKey(), MediaUrlSigner.attachment(photo), notAfter);
    }

    private String presign(String objectKey, String contentDisposition, Instant notAfter) {
        Duration duration = ttl;
        if (notAfter != null) {
            Duration left = Duration.between(Instant.now(), notAfter);
            if (left.compareTo(duration) < 0) duration = left.compareTo(MIN) < 0 ? MIN : left;
        }
        Duration signatureDuration = duration;
        return presigner.presignGetObject(r -> r.signatureDuration(signatureDuration)
                .getObjectRequest(g -> g.bucket(bucket).key(objectKey).responseContentDisposition(contentDisposition))).url().toString();
    }
}
