package io.kestra.plugin.sent;

import java.time.Duration;
import java.util.function.BooleanSupplier;

@FunctionalInterface
interface SentSleeper {
    void sleep(Duration duration) throws InterruptedException;

    default void checkCancelled() throws InterruptedException {
    }

    static SentSleeper cancellable(BooleanSupplier killed) {
        return new SentSleeper() {
            @Override
            public void checkCancelled() throws InterruptedException {
                if (killed.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Sent task was cancelled.");
                }
            }

            @Override
            public void sleep(Duration duration) throws InterruptedException {
                var remaining = duration;
                checkCancelled();
                while (!remaining.isZero() && !remaining.isNegative()) {
                    var slice = remaining.compareTo(Duration.ofMillis(100)) > 0 ? Duration.ofMillis(100) : remaining;
                    Thread.sleep(slice);
                    remaining = remaining.minus(slice);
                    checkCancelled();
                }
            }
        };
    }
}
