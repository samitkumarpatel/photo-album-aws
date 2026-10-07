package net.samitkumar.photo_album_aws.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Local and single-process deployments: processes each upload on its own virtual thread, so the upload request
 * returns at once. Work in flight is lost on shutdown; the photo then stays in its pre-processing status.
 */
@Component
@ConditionalOnProperty(prefix = "spring.application.processing", name = "mode", havingValue = "in-process", matchIfMissing = true)
class InProcessProcessingTrigger implements ProcessingTrigger, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(InProcessProcessingTrigger.class);
    private final PhotoProcessor processor;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    InProcessProcessingTrigger(PhotoProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void uploaded(UUID albumId, UUID photoId) {
        executor.execute(() -> {
            try {
                processor.process(albumId, photoId);
            } catch (Exception e) {
                log.error("Processing photo {} in album {} failed", photoId, albumId, e);
                try {
                    processor.markFailed(albumId, photoId, null);
                } catch (RuntimeException markError) {
                    log.error("Could not mark photo {} as failed", photoId, markError);
                }
            }
        });
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
    }
}
