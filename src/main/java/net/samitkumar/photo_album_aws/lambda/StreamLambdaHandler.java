package net.samitkumar.photo_album_aws.lambda;

import com.amazonaws.serverless.exceptions.ContainerInitializationException;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequest;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import net.samitkumar.photo_album_aws.PhotoAlbumAwsApplication;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * REST API entry point for the Lambda function URL (payload format 2.0, the same shape as HTTP API v2).
 * Handler string: {@code net.samitkumar.photo_album_aws.lambda.StreamLambdaHandler::handleRequest}.
 */
public class StreamLambdaHandler implements RequestStreamHandler {
    // Retained for the legacy Java handler; the native container uses Lambda Web Adapter.
    private static final SpringBootLambdaContainerHandler<HttpApiV2ProxyRequest, AwsProxyResponse> handler;

    static {
        try {
            handler = SpringBootLambdaContainerHandler.getHttpApiV2ProxyHandler(PhotoAlbumAwsApplication.class);
        } catch (ContainerInitializationException e) {
            throw new IllegalStateException("Could not initialise the Spring Boot application", e);
        }
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        handler.proxyStream(input, output, context);
    }
}
