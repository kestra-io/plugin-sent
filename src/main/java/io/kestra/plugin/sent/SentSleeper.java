package io.kestra.plugin.sent;

import java.time.Duration;

@FunctionalInterface
interface SentSleeper {
    SentSleeper THREAD_SLEEPER = duration -> Thread.sleep(duration.toMillis());

    void sleep(Duration duration) throws InterruptedException;
}
