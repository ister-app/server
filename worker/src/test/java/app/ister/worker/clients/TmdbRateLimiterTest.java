package app.ister.worker.clients;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TmdbRateLimiterTest {

    @Test
    void acquiresWithinLimitDoNotBlock() {
        TmdbRateLimiter subject = new TmdbRateLimiter(10);

        long start = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            subject.acquire();
        }
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 500, "10 acquires within the limit should not block, took " + elapsed + "ms");
    }

    @Test
    void acquireBeyondLimitWaitsForNextWindow() {
        TmdbRateLimiter subject = new TmdbRateLimiter(2);

        long start = System.currentTimeMillis();
        subject.acquire();
        subject.acquire();
        subject.acquire(); // third acquire has to wait for the next one-second window
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed >= 900, "third acquire should wait for the next window, took " + elapsed + "ms");
    }
}
