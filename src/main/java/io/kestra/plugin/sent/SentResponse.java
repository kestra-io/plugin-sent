package io.kestra.plugin.sent;

import java.util.Map;

public record SentResponse(
    int statusCode,
    Map<String, Object> data,
    String requestId,
    boolean idempotentReplayed,
    boolean sandbox) {
}
