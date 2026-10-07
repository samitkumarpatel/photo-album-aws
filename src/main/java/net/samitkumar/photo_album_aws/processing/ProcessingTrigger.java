package net.samitkumar.photo_album_aws.processing;

import java.util.UUID;

/**
 * Starts processing of a stored original: magic-byte check, metadata, derivatives, final status.
 *
 * <p>Locally this runs in the API process. On AWS, S3 event notifications feed the worker Lambda through SQS, so
 * the API-side trigger does nothing.
 */
public interface ProcessingTrigger {
    void uploaded(UUID albumId, UUID photoId);
}
