package io.kestra.plugin.sent;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SentValidationTest {
    @Test
    void acceptsAndDeduplicatesRecipients() {
        assertEquals(List.of("+12025550123"), SentValidation.recipients(List.of("+12025550123", "+12025550123")));
    }

    @Test
    void rejectsNonE164Recipient() {
        assertThrows(IllegalArgumentException.class, () -> SentValidation.recipients(List.of("202-555-0123")));
    }

    @Test
    void rejectsEmptyRecipients() {
        assertThrows(IllegalArgumentException.class, () -> SentValidation.recipients(List.of()));
    }

    @Test
    void normalizesAndDeduplicatesChannels() {
        assertEquals(List.of("sms", "rcs"), SentValidation.channels(List.of("SMS", "sms", "rcs")));
    }

    @Test
    void rejectsUnknownChannel() {
        assertThrows(IllegalArgumentException.class, () -> SentValidation.channels(List.of("email")));
    }

    @Test
    void acceptsDocumentedIdempotencyKey() {
        assertEquals("order_123-retry", SentValidation.idempotencyKey("order_123-retry"));
    }

    @Test
    void rejectsUnsafeIdempotencyKey() {
        assertThrows(IllegalArgumentException.class, () -> SentValidation.idempotencyKey("contains spaces"));
    }
}
