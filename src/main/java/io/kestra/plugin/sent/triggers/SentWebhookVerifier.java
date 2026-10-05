package io.kestra.plugin.sent.triggers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SentWebhookVerifier {
    private static final String PREFIX = "whsec_";

    private SentWebhookVerifier() {
    }

    public static Result verify(
        String secret,
        String webhookId,
        String timestamp,
        String signature,
        byte[] rawBody,
        Duration tolerance,
        Clock clock) {
        if (missing(webhookId) || missing(timestamp) || missing(signature)) {
            return Result.MISSING_HEADERS;
        }
        if (missing(secret) || !secret.startsWith(PREFIX)) {
            return Result.INVALID_SECRET;
        }

        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return Result.INVALID_TIMESTAMP;
        }

        Duration age;
        try {
            age = Duration.between(Instant.ofEpochSecond(timestampSeconds), clock.instant()).abs();
        } catch (RuntimeException e) {
            return Result.INVALID_TIMESTAMP;
        }
        if (age.compareTo(tolerance) > 0) {
            return Result.STALE;
        }

        String expected;
        try {
            expected = computeSignature(secret, webhookId, timestamp, rawBody);
        } catch (RuntimeException e) {
            return Result.INVALID_SECRET;
        }
        var equal = MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            signature.getBytes(StandardCharsets.US_ASCII)
        );
        return equal ? Result.VALID : Result.INVALID_SIGNATURE;
    }

    static String computeSignature(String secret, String webhookId, String timestamp, byte[] rawBody) {
        if (secret == null || !secret.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Invalid Sent webhook signing secret.");
        }
        try {
            var key = Base64.getDecoder().decode(secret.substring(PREFIX.length()));
            if (key.length == 0) {
                throw new IllegalArgumentException("Invalid Sent webhook signing secret.");
            }
            var prefix = (webhookId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8);
            var signed = new ByteArrayOutputStream(prefix.length + rawBody.length);
            signed.writeBytes(prefix);
            signed.writeBytes(rawBody);

            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(signed.toByteArray()));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.", e);
        }
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }

    public enum Result {
        VALID,
        MISSING_HEADERS,
        INVALID_TIMESTAMP,
        STALE,
        INVALID_SIGNATURE,
        INVALID_SECRET
    }
}
