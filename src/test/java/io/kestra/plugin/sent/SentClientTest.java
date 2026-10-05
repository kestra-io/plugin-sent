package io.kestra.plugin.sent;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KestraTest
@WireMockTest
class SentClientTest {
    private static final String API_KEY = "sent-test-secret";

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void transportFailuresAreRetriedWithoutLeakingTheirCause() throws Exception {
        var attempts = new AtomicInteger();
        var delays = new ArrayList<Duration>();
        var runContext = runContextFactory.of(Map.of());
        try (var transport = new HttpClient(runContext, HttpConfiguration.builder().build()) {
            @Override
            public <T> HttpResponse<T> request(HttpRequest request, Class<T> type) throws HttpClientException {
                attempts.incrementAndGet();
                throw new HttpClientException("Transport exposed x-api-key: " + API_KEY) {
                };
            }
        };
            var client = new SentClient(
                runContext, transport, URI.create("https://example.invalid/v3"),
                API_KEY, null, new SentRetryPolicy(3, Duration.ofMillis(1), Duration.ofSeconds(5)), delays::add
            )
        ) {
            var error = assertThrows(SentApiException.class, () -> client.get(List.of("me"), Map.of()));
            assertFalse(error.getMessage().contains(API_KEY));
            assertNull(error.getCause());
        }
        assertEquals(3, attempts.get());
        assertEquals(2, delays.size());
    }

    @Test
    void programmingErrorsAreNotRetriedOrRelabeled() throws Exception {
        var expected = new IllegalStateException("programming error");
        var attempts = new AtomicInteger();
        var delays = new ArrayList<Duration>();
        var runContext = runContextFactory.of(Map.of());
        try (var transport = new HttpClient(runContext, HttpConfiguration.builder().build()) {
            @Override
            public <T> HttpResponse<T> request(HttpRequest request, Class<T> type) {
                attempts.incrementAndGet();
                throw expected;
            }
        };
            var client = new SentClient(
                runContext, transport, URI.create("https://example.invalid/v3"),
                API_KEY, null, new SentRetryPolicy(3, Duration.ofMillis(1), Duration.ofSeconds(5)), delays::add
            )
        ) {
            assertSame(expected, assertThrows(IllegalStateException.class, () -> client.get(List.of("me"), Map.of())));
        }
        assertEquals(1, attempts.get());
        assertTrue(delays.isEmpty());
    }

    @Test
    void getAddsAuthProfileAndParsesMetadata(WireMockRuntimeInfo info) throws Exception {
        stubFor(
            get(urlPathEqualTo("/v3/contacts"))
                .withQueryParam("page", equalTo("2"))
                .withQueryParam("page_size", equalTo("25"))
                .withHeader("x-api-key", equalTo(API_KEY))
                .withHeader("x-profile-id", equalTo("profile-1"))
                .willReturn(
                    okJson(success("{\"contacts\":[],\"pagination\":{\"page\":2,\"has_more\":false}}", "req-body"))
                        .withHeader("X-Request-Id", "req-header")
                )
        );

        try (var client = client(info, "profile-1", 1, ignored ->
        {
        })) {
            var response = client.get(List.of("contacts"), Map.of("page", 2, "page_size", 25));
            assertEquals("req-body", response.requestId());
            assertEquals(200, response.statusCode());
            assertFalse(response.idempotentReplayed());
        }
    }

    @Test
    void postAddsIdempotencyAndReturnsReplaySandboxHeaders(WireMockRuntimeInfo info) throws Exception {
        stubFor(
            post(urlPathEqualTo("/v3/messages"))
                .withHeader("Idempotency-Key", equalTo("order_123"))
                .withRequestBody(equalToJson("{\"to\":[\"+12025550123\"],\"sandbox\":true}"))
                .willReturn(
                    aResponse().withStatus(202).withHeader("Content-Type", "application/json")
                        .withHeader("Idempotent-Replayed", "true").withHeader("X-Sandbox", "true")
                        .withBody(success("{\"status\":\"QUEUED\"}", "req-send"))
                )
        );

        try (var client = client(info, null, 1, ignored ->
        {
        })) {
            var response = client.post(
                List.of("messages"),
                Map.of("to", List.of("+12025550123"), "sandbox", true),
                "order_123"
            );
            assertTrue(response.idempotentReplayed());
            assertTrue(response.sandbox());
            assertEquals("QUEUED", response.data().get("status"));
        }
    }

    @Test
    void retriesGetAfterRateLimitAndHonorsBoundedDelay(WireMockRuntimeInfo info) throws Exception {
        stubFor(
            get(urlPathEqualTo("/v3/me")).inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse().withStatus(500).withHeader("Retry-After", "120").withHeader("Content-Type", "application/json")
                        .withBody(error("UNAVAILABLE", "Try later", "req-rate"))
                )
                .willSetStateTo("ok")
        );
        stubFor(
            get(urlPathEqualTo("/v3/me")).inScenario("retry").whenScenarioStateIs("ok")
                .willReturn(okJson(success("{\"id\":\"account-1\"}", "req-ok")))
        );
        var delays = new ArrayList<Duration>();

        try (var client = client(info, null, 3, delays::add)) {
            assertEquals("account-1", client.get(List.of("me"), Map.of()).data().get("id"));
        }
        assertEquals(List.of(Duration.ofSeconds(5)), delays);
        verify(2, getRequestedFor(urlPathEqualTo("/v3/me")));
    }

    @Test
    void rejectsMutationWithoutIdempotencyKeyBeforeNetwork(WireMockRuntimeInfo info) throws Exception {
        stubFor(
            post(urlPathEqualTo("/v3/messages"))
                .willReturn(
                    aResponse().withStatus(503).withHeader("Content-Type", "application/json")
                        .withBody(error("UNAVAILABLE", "Try later", "req-down"))
                )
        );

        try (var client = client(info, null, 3, ignored ->
        {
        })) {
            assertThrows(IllegalArgumentException.class, () -> client.request("POST", List.of("messages"), Map.of(), Map.of("sandbox", true), null));
        }
        verify(exactly(0), postRequestedFor(urlPathEqualTo("/v3/messages")));
    }

    @Test
    void mapsStructuredErrorWithoutLeakingCredential(WireMockRuntimeInfo info) throws Exception {
        stubFor(
            get(urlPathEqualTo("/v3/me"))
                .willReturn(
                    aResponse().withStatus(401).withHeader("Content-Type", "application/json")
                        .withBody(error("AUTH_001", "Rejected key " + API_KEY, "req-auth"))
                )
        );

        try (var client = client(info, null, 1, ignored ->
        {
        })) {
            var exception = assertThrows(SentApiException.class, () -> client.get(List.of("me"), Map.of()));
            assertEquals(401, exception.getStatusCode());
            assertEquals("AUTH_001", exception.getErrorCode());
            assertEquals("req-auth", exception.getRequestId());
            assertFalse(exception.getMessage().contains(API_KEY));
            assertTrue(exception.getMessage().contains("[redacted]"));
        }
    }

    @Test
    void rejectsMalformedSuccessfulEnvelope(WireMockRuntimeInfo info) throws Exception {
        stubFor(get(urlPathEqualTo("/v3/me")).willReturn(okJson("{\"unexpected\":true}")));
        try (var client = client(info, null, 1, ignored ->
        {
        })) {
            var exception = assertThrows(SentApiException.class, () -> client.get(List.of("me"), Map.of()));
            assertTrue(exception.getMessage().contains("unexpected response envelope"));
        }
    }

    @Test
    void encodesEveryPathSegment(WireMockRuntimeInfo info) throws Exception {
        stubFor(
            get(urlPathEqualTo("/v3/numbers/lookup/%2B12025550123"))
                .willReturn(okJson(success("{\"valid\":true}", "req-number")))
        );
        try (var client = client(info, null, 1, ignored ->
        {
        })) {
            assertEquals(true, client.get(List.of("numbers", "lookup", "+12025550123"), Map.of()).data().get("valid"));
        }
    }

    private SentClient client(WireMockRuntimeInfo info, String profileId, int attempts, SentSleeper sleeper) throws Exception {
        var runContext = runContextFactory.of(Map.of());
        var httpClient = HttpClient.builder().runContext(runContext).build();
        return new SentClient(
            runContext,
            httpClient,
            URI.create(info.getHttpBaseUrl() + "/v3"),
            API_KEY,
            profileId,
            new SentRetryPolicy(attempts, Duration.ofMillis(1), Duration.ofSeconds(5)),
            sleeper
        );
    }

    private static String success(String data, String requestId) {
        return "{\"success\":true,\"data\":" + data + ",\"error\":null,\"meta\":{\"request_id\":\"" + requestId + "\",\"version\":\"v3\"}}";
    }

    private static String error(String code, String message, String requestId) {
        return "{\"success\":false,\"data\":null,\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"},\"meta\":{\"request_id\":\"" + requestId + "\"}}";
    }
}
