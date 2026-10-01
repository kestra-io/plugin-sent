package io.kestra.plugin.sent;

import java.time.Duration;

record SentRetryPolicy(int maxAttempts, Duration initialInterval, Duration maxInterval) {
}
