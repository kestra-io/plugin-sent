package io.kestra.plugin.sent;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SentSleeperTest {
    @Test
    void cancellationStopsBackoffPromptly() throws Exception {
        var killed = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        var outcome = new AtomicReference<Throwable>();
        var worker = new Thread(() ->
        {
            try {
                SentSleeper.cancellable(() ->
                {
                    var cancelled = killed.get();
                    entered.countDown();
                    return cancelled;
                }).sleep(Duration.ofSeconds(30));
            } catch (Throwable error) {
                outcome.set(error);
            }
        });
        worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            killed.set(true);
            worker.join(2000);
            assertFalse(worker.isAlive());
            assertInstanceOf(InterruptedException.class, outcome.get());
        } finally {
            worker.interrupt();
            worker.join(2000);
        }
    }

    @Test
    void alreadyCancelledRequestDoesNotStart() {
        assertThrows(InterruptedException.class, () -> SentSleeper.cancellable(() -> true).checkCancelled());
    }
}
