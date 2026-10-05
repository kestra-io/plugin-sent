package io.kestra.plugin.sent.triggers;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentWebhookVerifierTest {
    private static final String SECRET = "whsec_abcdef1234567890";
    private static final String ID = "1f2e3d4c-5b6a-7980-1234-567890abcdef";
    private static final String TIMESTAMP = "1750000000";
    private static final byte[] BODY = "{\"field\":\"message\",\"event\":\"message.delivered\"}".getBytes(StandardCharsets.UTF_8);
    private static final String EXPECTED = "v1,toBvqGNFsjQ5xSZO93Z3qiyVckIbArJtTaEPaUYZJGw=";
    private static final Clock AT_TIMESTAMP = Clock.fixed(Instant.ofEpochSecond(1_750_000_000L), ZoneOffset.UTC);

    @Test
    void matchesOfficialSentVector() {
        assertEquals(EXPECTED, SentWebhookVerifier.computeSignature(SECRET, ID, TIMESTAMP, BODY));
    }

    @Test
    void acceptsValidFreshSignature() {
        assertEquals(
            SentWebhookVerifier.Result.VALID,
            SentWebhookVerifier.verify(SECRET, ID, TIMESTAMP, EXPECTED, BODY, Duration.ofMinutes(5), AT_TIMESTAMP)
        );
    }

    @Test
    void rejectsTamperedBody() {
        assertEquals(
            SentWebhookVerifier.Result.INVALID_SIGNATURE,
            SentWebhookVerifier.verify(SECRET, ID, TIMESTAMP, EXPECTED, "{}".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(5), AT_TIMESTAMP)
        );
    }

    @Test
    void rejectsStaleTimestamp() {
        var later = Clock.fixed(Instant.ofEpochSecond(1_750_000_301L), ZoneOffset.UTC);
        assertEquals(
            SentWebhookVerifier.Result.STALE,
            SentWebhookVerifier.verify(SECRET, ID, TIMESTAMP, EXPECTED, BODY, Duration.ofMinutes(5), later)
        );
    }

    @Test
    void acceptsTimestampAtBoundary() {
        var boundary = Clock.fixed(Instant.ofEpochSecond(1_750_000_300L), ZoneOffset.UTC);
        assertEquals(
            SentWebhookVerifier.Result.VALID,
            SentWebhookVerifier.verify(SECRET, ID, TIMESTAMP, EXPECTED, BODY, Duration.ofMinutes(5), boundary)
        );
    }

    @Test
    void rejectsMissingHeadersAndBadSecret() {
        assertEquals(
            SentWebhookVerifier.Result.MISSING_HEADERS,
            SentWebhookVerifier.verify(SECRET, null, TIMESTAMP, EXPECTED, BODY, Duration.ofMinutes(5), AT_TIMESTAMP)
        );
        assertEquals(
            SentWebhookVerifier.Result.INVALID_SECRET,
            SentWebhookVerifier.verify("not-a-secret", ID, TIMESTAMP, EXPECTED, BODY, Duration.ofMinutes(5), AT_TIMESTAMP)
        );
    }

    @Test
    void rawWhitespaceChangesSignature() {
        byte[] spaced = "{ \"field\": \"message\", \"event\": \"message.delivered\" }".getBytes(StandardCharsets.UTF_8);
        assertNotEquals(EXPECTED, SentWebhookVerifier.computeSignature(SECRET, ID, TIMESTAMP, spaced));
    }

    @Test
    void eventFilterSupportsExactParentAndWildcard() {
        assertTrue(EventTrigger.accepts(List.of("message.received"), "message", "message.received"));
        assertTrue(EventTrigger.accepts(List.of("message"), "message", "message.failed"));
        assertTrue(EventTrigger.accepts(List.of("*"), "templates", "template.approved"));
    }
}
