package io.kestra.plugin.sent.triggers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kestra.core.async.AsyncOperationProcessedEvent;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.services.WebhookService;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.core.trigger.AbstractWebhookTrigger;
import io.kestra.plugin.core.trigger.WebhookContext;

import jakarta.inject.Inject;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

@KestraTest
class EventTriggerTest {
    private static final String SECRET = "whsec_abcdef1234567890";
    private static final String BODY = "{\"field\":\"message\",\"event\":\"message.received\",\"timestamp\":\"2026-10-01T12:00:00Z\",\"payload\":{\"message_id\":\"test-message\",\"text\":\"Test café\"}}";

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private WebhookService webhookService;

    private EventTrigger trigger;
    private Flow flow;
    private RecordingWebhookService recording;

    @BeforeEach
    void setup() {
        trigger = EventTrigger.builder().id(IdUtils.create()).type(EventTrigger.class.getName())
            .key("test-url-key").signingSecret(Property.ofValue(SECRET)).build();
        flow = Flow.builder().id("sent_" + IdUtils.create()).namespace("qa.sent").revision(1)
            .tasks(List.of()).triggers(List.of(trigger)).build();
        recording = new RecordingWebhookService();
    }

    @Test
    void validSignatureCreatesExecutionWithVerifiedOutput() throws Exception {
        var response = evaluate(BODY, BODY, Instant.now().getEpochSecond(), false);
        assertEquals(HttpResponse.Status.OK, response.getStatus());
        assertEquals(1, recording.started.size());
        var execution = recording.started.getFirst();
        assertEquals(flow.getId(), execution.getFlowId());
        assertEquals(trigger.getId(), execution.getTrigger().getId());
        assertEquals("message.received", recording.output.getEvent());
        assertEquals("Test café", recording.output.getPayload().get("text"));
        assertTrue(recording.output.getDedupeKey().startsWith("event:message.received:"));
    }

    @Test
    void byteArrayBodyPreservesSignedUtf8() throws Exception {
        assertEquals(HttpResponse.Status.OK, evaluate(BODY, BODY, Instant.now().getEpochSecond(), true).getStatus());
        assertEquals(1, recording.started.size());
        assertEquals("Test café", recording.output.getPayload().get("text"));
    }

    @Test
    void tamperingCannotCreateAnExecution() throws Exception {
        assertEquals(HttpResponse.Status.UNAUTHORIZED, evaluate(BODY + " ", BODY, Instant.now().getEpochSecond(), false).getStatus());
        assertTrue(recording.started.isEmpty());
        assertNull(recording.output);
    }

    @Test
    void staleSignatureCannotCreateAnExecution() throws Exception {
        assertEquals(HttpResponse.Status.UNAUTHORIZED, evaluate(BODY, BODY, Instant.now().getEpochSecond() - 600, false).getStatus());
        assertTrue(recording.started.isEmpty());
    }

    @Test
    void validButFilteredEventDoesNotCreateAnExecution() throws Exception {
        var delivered = BODY.replace("message.received", "message.delivered");
        assertEquals(HttpResponse.Status.NO_CONTENT, evaluate(delivered, delivered, Instant.now().getEpochSecond(), false).getStatus());
        assertTrue(recording.started.isEmpty());
    }

    @Test
    void signedNullJsonIsRejectedWithoutAnExecution() throws Exception {
        assertEquals(HttpResponse.Status.BAD_REQUEST, evaluate("null", "null", Instant.now().getEpochSecond(), false).getStatus());
        assertTrue(recording.started.isEmpty());
    }

    @Test
    void missingSignatureIsRejectedWithoutAnExecution() throws Exception {
        var request = HttpRequest.builder().method("POST").uri(URI.create("https://localhost/webhook/test"))
            .body(HttpRequest.StringRequestBody.of(BODY)).build();
        var context = WebhookContext.builder().request(request).flow(flow).trigger(trigger).webhookService(recording).build();
        assertEquals(HttpResponse.Status.BAD_REQUEST, trigger.evaluate(context).block().getStatus());
        assertTrue(recording.started.isEmpty());
    }

    private HttpResponse<?> evaluate(String body, String signedBody, long timestamp, boolean bytes) throws Exception {
        var time = Long.toString(timestamp);
        var signature = SentWebhookVerifier.computeSignature(SECRET, "test-endpoint", time, signedBody.getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.builder().method("POST").uri(URI.create("https://localhost/webhook/test"))
            .addHeader("X-Webhook-ID", "test-endpoint")
            .addHeader("X-Webhook-Timestamp", time)
            .addHeader("X-Webhook-Signature", signature)
            .body(bytes ? HttpRequest.ByteArrayRequestBody.of(body.getBytes(StandardCharsets.UTF_8)) : HttpRequest.StringRequestBody.of(body))
            .build();
        var context = WebhookContext.builder().request(request).flow(flow).trigger(trigger).webhookService(recording).build();
        return trigger.evaluate(context).block();
    }

    private class RecordingWebhookService extends WebhookService {
        private final List<Execution> started = new ArrayList<>();
        private EventTrigger.Output output;

        @Override
        public RunContext runContext(Flow target, AbstractTrigger source) {
            return runContextFactory.of(Map.of());
        }

        @Override
        public Optional<Execution> newExecution(WebhookContext context, Flow target, AbstractWebhookTrigger source, io.kestra.core.models.tasks.Output value) {
            output = (EventTrigger.Output) value;
            return webhookService.newExecution(context, target, source, value);
        }

        @Override
        public Mono<AsyncOperationProcessedEvent> startExecution(Execution execution) {
            started.add(execution);
            return Mono.empty();
        }
    }
}
