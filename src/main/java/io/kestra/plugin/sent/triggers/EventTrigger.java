package io.kestra.plugin.sent.triggers;

import java.net.http.HttpHeaders;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.core.trigger.AbstractWebhookTrigger;
import io.kestra.plugin.core.trigger.WebhookContext;
import io.kestra.plugin.core.trigger.WebhookInputRenderException;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import reactor.core.publisher.Mono;

@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow from a signed Sent webhook",
    description = "Receives Sent events at Kestra's native webhook URL. It requires `X-Webhook-ID`, `X-Webhook-Timestamp`, and `X-Webhook-Signature`; verifies HMAC-SHA256 against the exact raw body in constant time; rejects stale deliveries; then parses and filters the event. Register the generated HTTPS URL manually in Sent."
)
@Plugin(examples = @Example(title = "Handle signed inbound-message events", full = true, code = """
    id: sent_inbound_events
    namespace: company.messaging

    tasks:
      - id: log_event_identifiers
        type: io.kestra.plugin.core.log.Log
        message: "Sent event {{ trigger.event }} with dedupe key {{ trigger.dedupeKey }}"

    triggers:
      - id: sent_webhook
        type: io.kestra.plugin.sent.triggers.EventTrigger
        key: "{{ secret('SENT_WEBHOOK_URL_KEY') }}"
        signingSecret: "{{ secret('SENT_WEBHOOK_SIGNING_SECRET') }}"
        events:
          - message.received
    """))
public class EventTrigger extends AbstractWebhookTrigger implements TriggerOutput<EventTrigger.Output> {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Schema(title = "Sent signing secret", description = "Webhook secret returned by Sent, including the `whsec_` prefix. Store it as a Kestra secret.")
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    private Property<String> signingSecret;

    @Schema(title = "Events", description = "Event names or parent fields to accept. Use `*` to accept every verified event.")
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<List<String>> events = Property.ofValue(List.of("message.received"));

    @Schema(title = "Replay tolerance", description = "Maximum absolute difference from `X-Webhook-Timestamp`. Sent recommends five minutes.")
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Duration> tolerance = Property.ofValue(Duration.ofMinutes(5));

    @Override
    public FetchType getFetchType() {
        return FetchType.FETCH;
    }

    @Override
    public Mono<HttpResponse<?>> evaluate(WebhookContext context) throws Exception {
        if (context.path() != null || context.request().getUri().getPath().endsWith("/")) {
            return Mono.just(HttpResponse.of(HttpResponse.Status.NOT_FOUND));
        }

        RawBody rawBody = rawBody(context.request().getBody());
        if (rawBody == null) {
            return Mono.just(HttpResponse.of(HttpResponse.Status.BAD_REQUEST));
        }

        RunContext runContext = context.webhookService().runContext(context.flow(), this);
        String rSigningSecret = runContext.render(signingSecret).as(String.class).orElse(null);
        Duration rTolerance = runContext.render(tolerance).as(Duration.class).orElse(Duration.ofMinutes(5));
        if (rTolerance.isNegative() || rTolerance.isZero()) {
            return Mono.just(HttpResponse.of(HttpResponse.Status.BAD_REQUEST));
        }

        HttpHeaders headers = context.request().getHeaders();
        String webhookId = header(headers, "X-Webhook-ID");
        String timestamp = header(headers, "X-Webhook-Timestamp");
        String signature = header(headers, "X-Webhook-Signature");
        SentWebhookVerifier.Result verification = SentWebhookVerifier.verify(
            rSigningSecret,
            webhookId,
            timestamp,
            signature,
            rawBody.bytes(),
            rTolerance,
            Clock.systemUTC()
        );
        if (verification == SentWebhookVerifier.Result.MISSING_HEADERS) {
            return Mono.just(HttpResponse.of(HttpResponse.Status.BAD_REQUEST));
        }
        if (verification != SentWebhookVerifier.Result.VALID) {
            return Mono.just(HttpResponse.of(HttpResponse.Status.UNAUTHORIZED));
        }

        Map<String, Object> event;
        try {
            event = MAPPER.readValue(rawBody.text(), MAP_TYPE);
        } catch (Exception e) {
            return Mono.just(HttpResponse.of(HttpResponse.Status.BAD_REQUEST));
        }
        if (event == null) {
            return Mono.just(HttpResponse.of(HttpResponse.Status.BAD_REQUEST));
        }
        String field = string(event.get("field"));
        String eventName = string(event.get("event"));
        String eventTimestamp = string(event.get("timestamp"));
        if (field == null || eventName == null || eventTimestamp == null || !(event.get("payload") instanceof Map<?, ?> rawPayload)) {
            return Mono.just(HttpResponse.of(HttpResponse.Status.BAD_REQUEST));
        }

        List<String> selected = runContext.render(events).asList(String.class);
        if (!accepts(selected, field, eventName)) {
            return Mono.just(HttpResponse.of(HttpResponse.Status.NO_CONTENT));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) rawPayload;
        Output output = Output.builder()
            .field(field)
            .event(eventName)
            .timestamp(eventTimestamp)
            .payload(payload)
            .webhookId(webhookId)
            .dedupeKey(dedupeKey(eventName, payload, rawBody.bytes()))
            .build();

        Optional<Execution> maybeExecution;
        try {
            maybeExecution = context.webhookService().newExecution(context, context.flow(), this, output);
        } catch (WebhookInputRenderException e) {
            return Mono.just(HttpResponse.of(HttpResponse.Status.UNPROCESSABLE_ENTITY));
        }
        if (maybeExecution.isEmpty()) {
            return Mono.just(HttpResponse.of(HttpResponse.Status.NO_CONTENT));
        }
        Execution execution = maybeExecution.get();
        return context.webhookService().startExecution(execution)
            .then(Mono.<HttpResponse<?>> just(HttpResponse.of(HttpResponse.Status.OK)))
            .onErrorResume(ignored -> Mono.just(HttpResponse.of(HttpResponse.Status.INTERNAL_SERVER_ERROR)));
    }

    static boolean accepts(List<String> selected, String field, String event) {
        return selected != null && selected.stream().filter(value -> value != null).map(String::trim)
            .anyMatch(value -> value.equals("*") || value.equals(field) || value.equals(event));
    }

    private static RawBody rawBody(HttpRequest.RequestBody body) {
        if (body instanceof HttpRequest.StringRequestBody text) {
            Charset charset = text.getCharset() == null ? StandardCharsets.UTF_8 : text.getCharset();
            return new RawBody(text.getContent().getBytes(charset), text.getContent());
        }
        if (body instanceof HttpRequest.ByteArrayRequestBody bytes) {
            Charset charset = bytes.getCharset() == null ? StandardCharsets.UTF_8 : bytes.getCharset();
            return new RawBody(bytes.getContent(), new String(bytes.getContent(), charset));
        }
        return null;
    }

    private static String header(HttpHeaders headers, String name) {
        return headers == null ? null : headers.firstValue(name).orElse(null);
    }

    private static String string(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String dedupeKey(String eventName, Map<String, Object> payload, byte[] rawBody) {
        String messageId = string(payload.get("message_id"));
        String status = string(payload.get("message_status"));
        if (messageId != null && status != null) {
            return "message:" + messageId + ":" + status;
        }
        return "event:" + eventName + ":" + sha256(rawBody);
    }

    private static String sha256(byte[] value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private record RawBody(byte[] bytes, String text) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Event field", description = "Parent Sent event family, such as message or templates.")
        private final String field;

        @Schema(title = "Event name", description = "Granular Sent event name, such as message.delivered.")
        private final String event;

        @Schema(title = "Event timestamp", description = "ISO 8601 event-creation timestamp from the signed body.")
        private final String timestamp;

        @Schema(title = "Event payload", description = "Event-specific payload from the verified request. It may contain phone numbers or message content; avoid logging it wholesale.")
        private final Map<String, Object> payload;

        @Schema(title = "Webhook ID", description = "Sent webhook configuration UUID. This is not a unique delivery ID.")
        private final String webhookId;

        @Schema(title = "Dedupe key", description = "Payload-derived candidate key. Persist it atomically in a shared store if the flow needs cross-execution deduplication.")
        private final String dedupeKey;
    }
}
