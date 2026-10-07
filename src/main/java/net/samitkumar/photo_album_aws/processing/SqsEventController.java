package net.samitkumar.photo_album_aws.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Receives SQS events forwarded by the Lambda Web Adapter in the worker function. */
@RestController
class SqsEventController {
    private static final Logger log = LoggerFactory.getLogger(SqsEventController.class);
    private final S3EventWorkerHandler handler;
    private final String lambdaRole;

    SqsEventController(PhotoProcessor processor,
                       @Value("${spring.application.lambda.role:api}") String lambdaRole) {
        this.handler = new S3EventWorkerHandler(processor);
        this.lambdaRole = lambdaRole;
    }

    @PostMapping("/events")
    BatchResponse events(@RequestBody JsonNode event) {
        if (!"worker".equals(lambdaRole)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var failures = new ArrayList<BatchItemFailure>();
        for (JsonNode message : event.path("Records")) {
            String messageId = message.path("messageId").asString(null);
            try {
                for (String key : S3EventWorkerHandler.objectKeys(message.path("body").asString(""))) {
                    handler.process(key);
                }
            } catch (Exception e) {
                log.error("Message {} failed and will be retried", messageId, e);
                failures.add(new BatchItemFailure(messageId));
            }
        }
        return new BatchResponse(failures);
    }

    record BatchResponse(List<BatchItemFailure> batchItemFailures) {}
    record BatchItemFailure(String itemIdentifier) {}
}
