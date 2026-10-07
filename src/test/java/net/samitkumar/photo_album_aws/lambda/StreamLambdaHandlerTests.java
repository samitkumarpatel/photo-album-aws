package net.samitkumar.photo_album_aws.lambda;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

// Drives the real handler with function URL events (payload format 2.0) against the default in-memory modes.
class StreamLambdaHandlerTests {
    private static final JsonMapper json = JsonMapper.builder().build();
    private final StreamLambdaHandler handler = new StreamLambdaHandler();

    @Test
    void createsAndListsAlbums() throws IOException {
        var created = invoke("POST", "/api/albums", "{\"name\":\"  Lisbon  \",\"description\":\"Trip\"}");
        assertEquals(201, created.get("statusCode").asInt());
        var album = json.readTree(created.get("body").asString());
        assertEquals("Lisbon", album.get("name").asString());
        assertFalse(album.get("id").asString().isBlank());

        var listed = invoke("GET", "/api/albums", null);
        assertEquals(200, listed.get("statusCode").asInt());
        var albums = json.readTree(listed.get("body").asString());
        assertTrue(albums.valueStream().anyMatch(a -> a.get("id").equals(album.get("id"))), albums.toString());
    }

    @Test
    void rejectsInvalidAlbum() throws IOException {
        assertEquals(400, invoke("POST", "/api/albums", "{\"name\":\" \"}").get("statusCode").asInt());
    }

    @Test
    void doesNotServeTheSpa() throws IOException {
        // Test configuration disables the SPA fallback for this API handler test.
        assertEquals(404, invoke("GET", "/albums", null).get("statusCode").asInt());
    }

    private JsonNode invoke(String method, String path, String body) throws IOException {
        var event = """
                {
                  "version": "2.0",
                  "routeKey": "$default",
                  "rawPath": "%s",
                  "rawQueryString": "",
                  "headers": {"accept": "application/json", "content-type": "application/json", "host": "abc.lambda-url.eu-north-1.on.aws"},
                  "requestContext": {
                    "accountId": "123456789012",
                    "apiId": "abc",
                    "domainName": "abc.lambda-url.eu-north-1.on.aws",
                    "domainPrefix": "abc",
                    "http": {"method": "%s", "path": "%s", "protocol": "HTTP/1.1", "sourceIp": "203.0.113.1", "userAgent": "test"},
                    "requestId": "req-1",
                    "routeKey": "$default",
                    "stage": "$default",
                    "time": "06/Oct/2026:10:00:00 +0000",
                    "timeEpoch": 1791280800000
                  },
                  "body": %s,
                  "isBase64Encoded": false
                }
                """.formatted(path, method, path, body == null ? "null" : json.writeValueAsString(body));
        var out = new ByteArrayOutputStream();
        handler.handleRequest(new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)), out, new TestContext());
        return json.readTree(out.toByteArray());
    }

    private static final class TestContext implements Context {
        public String getAwsRequestId() { return "req-1"; }
        public String getLogGroupName() { return "/aws/lambda/test"; }
        public String getLogStreamName() { return "test"; }
        public String getFunctionName() { return "test"; }
        public String getFunctionVersion() { return "$LATEST"; }
        public String getInvokedFunctionArn() { return "arn:aws:lambda:eu-north-1:123456789012:function:test"; }
        public CognitoIdentity getIdentity() { return null; }
        public ClientContext getClientContext() { return null; }
        public int getRemainingTimeInMillis() { return 30_000; }
        public int getMemoryLimitInMB() { return 2048; }
        public LambdaLogger getLogger() {
            return new LambdaLogger() {
                public void log(String message) { System.out.println(message); }
                public void log(byte[] message) { System.out.println(new String(message, StandardCharsets.UTF_8)); }
            };
        }
    }
}
