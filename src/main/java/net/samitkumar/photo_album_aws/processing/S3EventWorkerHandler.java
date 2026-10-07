package net.samitkumar.photo_album_aws.processing;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Worker Lambda: S3 ObjectCreated notifications for {@code originals/} arrive through SQS, one notification per message
 * body (optionally wrapped in an SNS envelope when S3 publishes to a topic that fans out to queues).
 *
 * <p>Failed messages are reported individually through {@link SQSBatchResponse}, so one bad file does not make SQS
 * redeliver the whole batch; this needs {@code ReportBatchItemFailures} on the event source mapping. Messages that keep
 * failing move to the dead-letter queue. Validation failures are not errors: the photo is marked failed and the message
 * is acknowledged.
 */
public class S3EventWorkerHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {
    private static final Logger log = LoggerFactory.getLogger(S3EventWorkerHandler.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PhotoProcessor processor;

    /** Used by the Lambda runtime. The Spring context starts with the first instance, during the init phase. */
    public S3EventWorkerHandler() {
        this(WorkerApplication.processor());
    }

    S3EventWorkerHandler(PhotoProcessor processor) {
        this.processor = processor;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        var failures = new ArrayList<SQSBatchResponse.BatchItemFailure>();
        List<SQSEvent.SQSMessage> messages = event.getRecords() == null ? List.of() : event.getRecords();
        for (SQSEvent.SQSMessage message : messages) {
            try {
                for (String key : objectKeys(message.getBody())) process(key);
            } catch (Exception e) {
                log.error("Message {} failed and will be retried", message.getMessageId(), e);
                failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
            }
        }
        return new SQSBatchResponse(failures);
    }

    private void process(String objectKey) throws Exception {
        Optional<OriginalKey> original = OriginalKey.parse(objectKey);
        if (original.isEmpty()) {
            log.debug("Ignoring {}: not an original", objectKey);
            return;
        }
        processor.process(original.get().albumId(), original.get().photoId(), objectKey);
    }

    /**
     * Created object keys in an S3 event notification, URL-decoded (S3 encodes them like form values, spaces as
     * {@code +}). The {@code s3:TestEvent} S3 sends when the notification is configured has none.
     */
    static List<String> objectKeys(String body) {
        JsonNode root = JSON.readTree(body);
        if ("Notification".equals(root.path("Type").asString(null)) && root.path("Message").isString()) {
            root = JSON.readTree(root.path("Message").asString());
        }
        if ("s3:TestEvent".equals(root.path("Event").asString(null))) return List.of();
        var keys = new ArrayList<String>();
        for (JsonNode record : root.path("Records")) {
            if (!record.path("eventName").asString("").startsWith("ObjectCreated")) continue;
            String key = record.path("s3").path("object").path("key").asString(null);
            if (key != null) keys.add(URLDecoder.decode(key, StandardCharsets.UTF_8));
        }
        return keys;
    }
}
