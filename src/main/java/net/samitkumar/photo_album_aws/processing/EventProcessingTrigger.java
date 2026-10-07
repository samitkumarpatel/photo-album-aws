package net.samitkumar.photo_album_aws.processing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * AWS: the API does nothing, because the S3 ObjectCreated notification for {@code originals/} goes through SQS to the
 * worker Lambda ({@link S3EventWorkerHandler}). That also covers presigned uploads the API never sees the bytes of.
 */
@Component
@ConditionalOnProperty(prefix = "spring.application.processing", name = "mode", havingValue = "events")
class EventProcessingTrigger implements ProcessingTrigger {
    @Override
    public void uploaded(UUID albumId, UUID photoId) {}
}
