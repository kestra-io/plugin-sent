package io.kestra.plugin.sent.triggers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs against the actual packaged plugin and Kestra ExecutionController.
 * Requires an isolated local Kestra server; no Sent API requests are made.
 */
@Tag("webhook-integration")
class EventTriggerRouteTest {
    private static final String SECRET = "whsec_abcdef1234567890";
    private static final String BODY = """
        { "field": "message", "event": "message.received",
          "timestamp": "2026-10-01T12:00:00Z",
          "payload": { "message_id": "test-message", "text": "Test café ☕" } }
        """;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String base;
    private String flowId;
    private String authorization;

    @BeforeEach
    void createIsolatedFlow() throws Exception {
        base = System.getProperty("sent.kestraUrl", "");
        assertFalse(base.isBlank(), "Run webhookIntegrationTest with -PkestraUrl=http://localhost:<isolated-test-port>");
        var uri = URI.create(base);
        assertTrue("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()), "Only a local test server is allowed.");
        var credentials = System.getProperty("sent.kestraUser") + ":" + System.getProperty("sent.kestraPassword");
        authorization = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        flowId = "signed_route_" + UUID.randomUUID().toString().replace("-", "");
        var flow = """
            id: %s
            namespace: qa.plugin.sent
            tasks:
              - id: verified_event
                type: io.kestra.plugin.core.log.Log
                message: "Verified synthetic Sent webhook"
            triggers:
              - id: sent_webhook
                type: io.kestra.plugin.sent.triggers.EventTrigger
                key: test-url-key
                signingSecret: "%s"
            """.formatted(flowId, SECRET);
        var response = request("/api/v1/main/flows")
            .header("Content-Type", "application/x-yaml")
            .POST(HttpRequest.BodyPublishers.ofString(flow)).build();
        var created = client.send(response, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, created.statusCode(), created.body());
    }

    @AfterEach
    void deleteTestFlow() throws Exception {
        if (flowId != null) {
            var response = client.send(request("/api/v1/main/flows/qa.plugin.sent/" + flowId).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode() == 204 || response.statusCode() == 200, response.body());
        }
    }

    @Test
    void signedUtf8BodyCreatesSuccessfulExecutionThroughRealRoute() throws Exception {
        assertEquals(200, webhook(BODY, BODY, Instant.now().getEpochSecond(), true));
        JsonNode execution = null;
        var deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            var results = executions().path("results");
            if (!results.isEmpty()) {
                assertEquals(1, results.size());
                execution = results.get(0);
                if ("SUCCESS".equals(execution.path("state").path("current").asText())) {
                    break;
                }
            }
            Thread.sleep(100);
        }
        assertNotNull(execution, "No execution reached the real Kestra queue.");
        assertEquals("SUCCESS", execution.path("state").path("current").asText(), execution.toString());
        var variables = execution.path("trigger").path("variables");
        assertEquals("message.received", variables.path("event").asText());
        assertEquals("Test café ☕", variables.path("payload").path("text").asText());
        assertTrue(variables.path("dedupeKey").asText().startsWith("event:message.received:"));
    }

    @Test
    void whitespaceTamperingIsRejectedByRealRoute() throws Exception {
        assertEquals(401, webhook(BODY + " ", BODY, Instant.now().getEpochSecond(), true));
        assertNoExecution();
    }

    @Test
    void staleSignatureIsRejectedByRealRoute() throws Exception {
        assertEquals(401, webhook(BODY, BODY, Instant.now().getEpochSecond() - 600, true));
        assertNoExecution();
    }

    @Test
    void missingSignatureIsRejectedByRealRoute() throws Exception {
        assertEquals(400, webhook(BODY, BODY, Instant.now().getEpochSecond(), false));
        assertNoExecution();
    }

    @Test
    void filteredSignedEventDoesNotCreateExecution() throws Exception {
        var filtered = BODY.replace("message.received", "message.delivered");
        assertEquals(204, webhook(filtered, filtered, Instant.now().getEpochSecond(), true));
        assertNoExecution();
    }

    private int webhook(String body, String signedBody, long timestamp, boolean signed) throws Exception {
        return webhook(body.getBytes(StandardCharsets.UTF_8), signedBody, timestamp, signed, "UTF-8");
    }

    @Test
    void lossyUtf8DecodingIsRejectedByRealRoute() throws Exception {
        var signedBody = BODY.replace("café", "\uFFFD");
        var bytes = signedBody.getBytes(StandardCharsets.UTF_8);
        for (var i = 0; i < bytes.length; i++) {
            if (bytes[i] == (byte) 0xef) {
                bytes[i] = (byte) 0xff;
                break;
            }
        }
        assertEquals(400, webhook(bytes, signedBody, Instant.now().getEpochSecond(), true, "UTF-8"));
        assertNoExecution();
    }

    @Test
    void nonUtf8CharsetIsRejectedByRealRoute() throws Exception {
        assertEquals(400, webhook(BODY.getBytes(StandardCharsets.ISO_8859_1), BODY, Instant.now().getEpochSecond(), true, "ISO-8859-1"));
        assertNoExecution();
    }

    private int webhook(byte[] body, String signedBody, long timestamp, boolean signed, String charset) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/api/v1/main/executions/webhook/qa.plugin.sent/" + flowId + "/test-url-key"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json; charset=" + charset)
            .header("X-Webhook-ID", "test-endpoint")
            .header("X-Webhook-Timestamp", Long.toString(timestamp))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (signed) {
            request.header("X-Webhook-Signature", SentWebhookVerifier.computeSignature(SECRET, "test-endpoint", Long.toString(timestamp), signedBody.getBytes(StandardCharsets.UTF_8)));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15)).header("Authorization", authorization);
    }

    private JsonNode executions() throws Exception {
        var response = client.send(request("/api/v1/main/executions/search?namespace=qa.plugin.sent&flowId=" + flowId).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }

    private void assertNoExecution() throws Exception {
        for (var attempt = 0; attempt < 10; attempt++) {
            assertEquals(0, executions().path("total").asInt(-1));
            Thread.sleep(100);
        }
    }
}
