package io.kestra.plugin.sent;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractSentConnection extends Task {
    static final String DEFAULT_BASE_URL = "https://api.sent.dm/v3";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    @Schema(
        title = "Sent API key",
        description = "API key sent in the `x-api-key` header. Store it as a Kestra secret."
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    private Property<String> apiKey;

    @Schema(
        title = "Sender Profile ID",
        description = "Optional Sender Profile UUID for `x-profile-id` scoping with an organization API key. Profile API keys must leave this unset."
    )
    @PluginProperty(group = "connection")
    private Property<String> profileId;

    @Schema(
        title = "Sent API base URL",
        description = "Sent API v3 origin. Keep the default in production. Custom HTTPS origins are intended for controlled proxies; plain HTTP is accepted only for loopback test servers."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

    @Schema(title = "Connection timeout", description = "Maximum time allowed to establish the Sent API connection.")
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Duration> connectTimeout = Property.ofValue(Duration.ofSeconds(10));

    @Schema(title = "Read timeout", description = "Maximum idle time while reading a Sent API response.")
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Duration> readTimeout = Property.ofValue(Duration.ofSeconds(30));

    @Schema(
        title = "Maximum request attempts",
        description = "Total attempts for retry-safe calls. GET requests are retry-safe; mutations retry only when an idempotency key is present."
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<@Min(1) @Max(10) Integer> maxAttempts = Property.ofValue(3);

    @Schema(title = "Initial retry interval", description = "Initial exponential-backoff delay for retry-safe transient failures.")
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Duration> initialRetryInterval = Property.ofValue(Duration.ofSeconds(1));

    @Schema(title = "Maximum retry interval", description = "Upper bound for exponential backoff and provider `Retry-After` delays.")
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Duration> maxRetryInterval = Property.ofValue(Duration.ofSeconds(30));

    protected SentClient client(RunContext runContext) throws Exception {
        return client(runContext, true);
    }

    protected SentClient client(RunContext runContext, boolean includeProfileScope) throws Exception {
        String rApiKey = runContext.render(apiKey).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("apiKey is required; configure a Sent API key using a Kestra secret."));
        if (rApiKey.isBlank()) {
            throw new IllegalArgumentException("Sent API key must not be blank.");
        }

        URI rBaseUri = validateBaseUri(runContext.render(baseUrl).as(String.class).orElse(DEFAULT_BASE_URL));
        String rProfileId = includeProfileScope ? optional(runContext.render(profileId).as(String.class).orElse(null)) : null;
        Duration rConnectTimeout = positive(runContext.render(connectTimeout).as(Duration.class).orElse(Duration.ofSeconds(10)), "connectTimeout");
        Duration rReadTimeout = positive(runContext.render(readTimeout).as(Duration.class).orElse(Duration.ofSeconds(30)), "readTimeout");
        int rMaxAttempts = runContext.render(maxAttempts).as(Integer.class).orElse(3);
        Duration rInitialRetryInterval = nonNegative(runContext.render(initialRetryInterval).as(Duration.class).orElse(Duration.ofSeconds(1)), "initialRetryInterval");
        Duration rMaxRetryInterval = positive(runContext.render(maxRetryInterval).as(Duration.class).orElse(Duration.ofSeconds(30)), "maxRetryInterval");

        if (rMaxAttempts < 1 || rMaxAttempts > 10) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 10.");
        }
        if (rInitialRetryInterval.compareTo(rMaxRetryInterval) > 0) {
            throw new IllegalArgumentException("initialRetryInterval must not exceed maxRetryInterval.");
        }

        HttpConfiguration configuration = HttpConfiguration.builder()
            .followRedirects(Property.ofValue(false))
            .timeout(
                TimeoutConfiguration.builder()
                    .connectTimeout(Property.ofValue(rConnectTimeout))
                    .readIdleTimeout(Property.ofValue(rReadTimeout))
                    .build()
            )
            .build();

        HttpClient httpClient = HttpClient.builder()
            .runContext(runContext)
            .configuration(configuration)
            .build();

        return new SentClient(
            runContext,
            httpClient,
            rBaseUri,
            rApiKey,
            rProfileId,
            new SentRetryPolicy(rMaxAttempts, rInitialRetryInterval, rMaxRetryInterval),
            SentSleeper.THREAD_SLEEPER
        );
    }

    private static URI validateBaseUri(String value) {
        URI uri;
        try {
            uri = URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseUrl must be a valid absolute HTTP(S) URL.", e);
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isBlank() || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new IllegalArgumentException("baseUrl must be an absolute HTTP(S) URL with a host.");
        }
        if (scheme.equals("http") && !LOOPBACK_HOSTS.contains(host)) {
            throw new IllegalArgumentException("baseUrl must use HTTPS outside loopback development.");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not contain credentials, a query, or a fragment.");
        }
        return uri;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Duration positive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private static Duration nonNegative(Duration value, String name) {
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative.");
        }
        return value;
    }
}
