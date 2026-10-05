package io.kestra.plugin.sent;

import java.io.Closeable;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientRequestException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

public final class SentClient implements Closeable {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    private static final int MAX_ERROR_LENGTH = 2048;

    private final RunContext runContext;
    private final HttpClient httpClient;
    private final URI baseUri;
    private final String apiKey;
    private final String profileId;
    private final SentRetryPolicy retryPolicy;
    private final SentSleeper sleeper;

    SentClient(
        RunContext runContext,
        HttpClient httpClient,
        URI baseUri,
        String apiKey,
        String profileId,
        SentRetryPolicy retryPolicy,
        SentSleeper sleeper) {
        this.runContext = runContext;
        this.httpClient = httpClient;
        this.baseUri = baseUri;
        this.apiKey = apiKey;
        this.profileId = profileId;
        this.retryPolicy = retryPolicy;
        this.sleeper = sleeper;
    }

    public SentResponse get(List<String> pathSegments, Map<String, Object> query) throws Exception {
        return request("GET", pathSegments, query, null, null);
    }

    public SentResponse post(List<String> pathSegments, Object body, String idempotencyKey) throws Exception {
        return request("POST", pathSegments, Map.of(), body, idempotencyKey);
    }

    public SentResponse patch(List<String> pathSegments, Object body, String idempotencyKey) throws Exception {
        return request("PATCH", pathSegments, Map.of(), body, idempotencyKey);
    }

    SentResponse request(
        String method,
        List<String> pathSegments,
        Map<String, Object> query,
        Object body,
        String idempotencyKey) throws Exception {
        var rIdempotencyKey = blankToNull(idempotencyKey);
        if (!method.equals("GET") && rIdempotencyKey == null) {
            throw new IllegalArgumentException("Sent mutations require a non-blank idempotency key.");
        }
        var retrySafe = method.equals("GET") || rIdempotencyKey != null;
        var request = buildRequest(method, pathSegments, query, body, rIdempotencyKey);

        for (var attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            sleeper.checkCancelled();
            try {
                var response = httpClient.request(request, String.class);
                return parse(response);
            } catch (HttpClientResponseException e) {
                var status = e.getResponse() == null || e.getResponse().getStatus() == null ? 0 : e.getResponse().getStatus().getCode();
                if (retrySafe && attempt < retryPolicy.maxAttempts() && retryableStatus(status)) {
                    retry(attempt, status, retryAfter(e));
                    continue;
                }
                throw mapError(e);
            } catch (HttpClientRequestException e) {
                if (retrySafe && attempt < retryPolicy.maxAttempts()) {
                    retry(attempt, 0, null);
                    continue;
                }
                throw networkError(method, rIdempotencyKey);
            } catch (IllegalVariableEvaluationException e) {
                throw e;
            } catch (HttpClientException e) {
                if (retrySafe && attempt < retryPolicy.maxAttempts()) {
                    retry(attempt, 0, null);
                    continue;
                }
                throw networkError(method, rIdempotencyKey);
            }
        }

        throw new IllegalStateException("Sent retry loop exited unexpectedly.");
    }

    private HttpRequest buildRequest(
        String method,
        List<String> pathSegments,
        Map<String, Object> query,
        Object body,
        String idempotencyKey) {
        var builder = HttpRequest.builder()
            .method(method)
            .uri(buildUri(pathSegments, query))
            .addHeader("Accept", "application/json")
            .addHeader("x-api-key", apiKey);

        if (profileId != null) {
            builder.addHeader("x-profile-id", profileId);
        }
        if (idempotencyKey != null) {
            builder.addHeader("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            builder
                .addHeader("Content-Type", "application/json")
                .body(HttpRequest.JsonRequestBody.of(body));
        }
        return builder.build();
    }

    private URI buildUri(List<String> pathSegments, Map<String, Object> query) {
        var value = new StringBuilder(baseUri.toString());
        for (var segment : pathSegments) {
            value.append('/').append(encode(segment));
        }

        var parameters = new ArrayList<String>();
        if (query != null) {
            query.forEach((key, rawValue) ->
            {
                if (rawValue != null && !(rawValue instanceof String text && text.isBlank())) {
                    parameters.add(encode(key) + "=" + encode(String.valueOf(rawValue)));
                }
            });
        }
        if (!parameters.isEmpty()) {
            value.append('?').append(String.join("&", parameters));
        }
        return URI.create(value.toString());
    }

    private SentResponse parse(HttpResponse<String> response) throws SentApiException {
        var body = response.getBody();
        SentEnvelope envelope;
        try {
            envelope = body == null || body.isBlank() ? null : MAPPER.readValue(body, SentEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new SentApiException("Sent returned malformed JSON in a successful response.", response.getStatus().getCode(), null, header(response.getHeaders(), "X-Request-Id"));
        }

        if (envelope == null || !Boolean.TRUE.equals(envelope.getSuccess())) {
            var error = envelope == null ? null : envelope.getError();
            var requestId = requestId(envelope, response.getHeaders());
            var message = error == null ? "Sent returned an unexpected response envelope." : safe(error.getMessage());
            var code = error == null ? null : safe(error.getCode());
            throw new SentApiException(formatError(response.getStatus().getCode(), code, requestId, message), response.getStatus().getCode(), code, requestId);
        }
        if (envelope.getData() == null) {
            throw new SentApiException("Sent returned a successful response without resource data.", response.getStatus().getCode(), null, requestId(envelope, response.getHeaders()));
        }

        return new SentResponse(
            response.getStatus().getCode(),
            envelope.getData(),
            requestId(envelope, response.getHeaders()),
            Boolean.parseBoolean(header(response.getHeaders(), "Idempotent-Replayed")),
            Boolean.parseBoolean(header(response.getHeaders(), "X-Sandbox"))
        );
    }

    private SentApiException mapError(HttpClientResponseException exception) {
        var status = exception.getResponse() == null || exception.getResponse().getStatus() == null ? 0 : exception.getResponse().getStatus().getCode();
        var rawBody = rawBody(exception);
        SentEnvelope envelope = null;
        try {
            envelope = rawBody.isBlank() ? null : MAPPER.readValue(rawBody, SentEnvelope.class);
        } catch (JsonProcessingException ignored) {
        }

        var error = envelope == null ? null : envelope.getError();
        var code = error == null ? null : safe(error.getCode());
        var requestId = requestId(envelope, exception.getResponse() == null ? null : exception.getResponse().getHeaders());
        var providerMessage = error == null ? defaultMessage(status) : safe(error.getMessage());
        return new SentApiException(formatError(status, code, requestId, providerMessage), status, code, requestId);
    }

    private SentApiException networkError(String method, String idempotencyKey) {
        var safety = method.equals("GET") || idempotencyKey != null
            ? "The request is retry-safe with the same configuration."
            : "A timed-out mutation may have been accepted; do not retry it without an idempotency key.";
        // Transport causes can contain request headers; keep them out of operator logs.
        return new SentApiException("Could not reach the Sent API. Check connectivity and timeouts. " + safety, 0, null, null);
    }

    private void retry(int attempt, int status, Duration retryAfter) throws SentApiException {
        var exponential = exponentialDelay(attempt);
        var delay = retryAfter == null ? exponential : max(exponential, min(retryAfter, retryPolicy.maxInterval()));
        runContext.logger().warn(
            "Retrying a safe Sent API request after {} (attempt {}/{}); waiting {}",
            status == 0 ? "a network failure" : "HTTP " + status,
            attempt + 1,
            retryPolicy.maxAttempts(),
            delay
        );
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SentApiException("Interrupted while waiting to retry the Sent API request.", e);
        }
    }

    private Duration exponentialDelay(int attempt) {
        var multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 30);
        Duration computed;
        try {
            computed = retryPolicy.initialInterval().multipliedBy(multiplier);
        } catch (ArithmeticException e) {
            computed = retryPolicy.maxInterval();
        }
        return min(computed, retryPolicy.maxInterval());
    }

    private Duration retryAfter(HttpClientResponseException exception) {
        if (exception.getResponse() == null || exception.getResponse().getHeaders() == null) {
            return null;
        }
        var value = header(exception.getResponse().getHeaders(), "Retry-After");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            try {
                var at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Duration.between(Instant.now(), at).isNegative() ? Duration.ZERO : Duration.between(Instant.now(), at);
            } catch (DateTimeParseException ignoredDate) {
                return null;
            }
        }
    }

    private String requestId(SentEnvelope envelope, HttpHeaders headers) {
        var fromBody = envelope != null && envelope.getMeta() != null ? envelope.getMeta().getRequestId() : null;
        return blankToNull(fromBody) != null ? safe(fromBody) : safe(header(headers, "X-Request-Id"));
    }

    private String rawBody(HttpClientResponseException exception) {
        var body = exception.getResponse() == null ? null : exception.getResponse().getBody();
        return switch (body) {
            case null -> "";
            case byte[] bytes -> new String(bytes, StandardCharsets.UTF_8);
            default -> String.valueOf(body);
        };
    }

    private String formatError(int status, String code, String requestId, String providerMessage) {
        var context = new ArrayList<String>();
        context.add("HTTP " + status);
        if (code != null) {
            context.add("code " + code);
        }
        if (requestId != null) {
            context.add("request ID " + requestId);
        }
        return "Sent API request failed (" + String.join(", ", context) + "): " + (providerMessage == null ? defaultMessage(status) : providerMessage);
    }

    private String safe(String value) {
        if (value == null) {
            return null;
        }
        var redacted = value
            .replace(apiKey, "[redacted]")
            .replace(encode(apiKey), "[redacted]");
        return redacted.length() <= MAX_ERROR_LENGTH ? redacted : redacted.substring(0, MAX_ERROR_LENGTH) + "…";
    }

    private static boolean retryableStatus(int status) {
        return status == 408 || status == 425 || status == 429 || (status >= 500 && status <= 599 && status != 501);
    }

    private static String defaultMessage(int status) {
        return switch (status) {
            case 400 -> "Sent rejected the request. Check the documented input constraints.";
            case 401 -> "The Sent API key is missing, expired, or invalid.";
            case 403 -> "The API key lacks permission or cannot use the requested Sender Profile.";
            case 404 -> "The requested Sent resource was not found.";
            case 409 -> "The request conflicts with an operation already in progress.";
            case 422 -> "Sent could not process one or more request fields.";
            case 429 -> "The Sent rate limit was reached.";
            default -> status >= 500 ? "Sent is temporarily unavailable." : "Sent returned an error.";
        };
    }

    private static String header(HttpHeaders headers, String name) {
        return headers == null ? null : headers.firstValue(name).orElse(null);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static Duration max(Duration first, Duration second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (Exception e) {
            runContext.logger().warn("Failed to close the Sent HTTP client cleanly.");
        }
    }
}
