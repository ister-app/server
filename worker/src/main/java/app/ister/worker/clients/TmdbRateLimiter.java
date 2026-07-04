package app.ister.worker.clients;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Simple token-bucket limiter for TMDB requests. TMDB allows roughly 40 requests
 * per second; the default of 30 keeps a safe margin. acquire() blocks until a
 * slot in the current one-second window is free.
 */
@Component
public class TmdbRateLimiter {
    private final int maxRequestsPerSecond;
    private long windowStartMillis;
    private int requestsInWindow;

    public TmdbRateLimiter(@Value("${app.ister.server.TMDB.max-requests-per-second:30}") int maxRequestsPerSecond) {
        this.maxRequestsPerSecond = maxRequestsPerSecond;
    }

    public synchronized void acquire() {
        while (true) {
            long now = System.currentTimeMillis();
            if (now - windowStartMillis >= 1000) {
                windowStartMillis = now;
                requestsInWindow = 0;
            }
            if (requestsInWindow < maxRequestsPerSecond) {
                requestsInWindow++;
                return;
            }
            long remaining = 1000 - (now - windowStartMillis);
            if (remaining <= 0) {
                continue;
            }
            try {
                wait(remaining);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
