package io.kestra.plugin.sent;

import java.util.Collection;
import java.util.Set;
import java.util.regex.Pattern;

public final class SentValidation {
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{1,14}$");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,255}$");
    private static final Set<String> CHANNELS = Set.of("sent", "sms", "whatsapp", "rcs");

    private SentValidation() {
    }

    public static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required and must not be blank.");
        }
        return value;
    }

    public static String e164(String value, String name) {
        String checked = required(value, name);
        if (!E164.matcher(checked).matches()) {
            throw new IllegalArgumentException(name + " must use E.164 format, for example +12025550123.");
        }
        return checked;
    }

    public static java.util.List<String> recipients(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("to must contain at least one E.164 recipient.");
        }
        return values.stream().map(value -> e164(value, "to recipient")).distinct().toList();
    }

    public static java.util.List<String> channels(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("channels must contain at least one channel.");
        }
        return values.stream()
            .map(value -> required(value, "channel").toLowerCase(java.util.Locale.ROOT))
            .peek(value ->
            {
                if (!CHANNELS.contains(value)) {
                    throw new IllegalArgumentException("channel must be one of sent, sms, whatsapp, or rcs.");
                }
            })
            .distinct()
            .toList();
    }

    public static String idempotencyKey(String value) {
        String checked = required(value, "idempotencyKey");
        if (!IDEMPOTENCY_KEY.matcher(checked).matches()) {
            throw new IllegalArgumentException("idempotencyKey must be 1-255 ASCII letters, digits, underscores, or hyphens.");
        }
        return checked;
    }
}
