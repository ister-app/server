package app.ister.worker.http;

/**
 * Enforces a minimum interval between outbound requests across all threads, for metadata
 * providers whose rate limit applies per client IP (MusicBrainz allows 1 request/second).
 * Callers each get a slot in arrival order and sleep outside the lock until their slot
 * starts, so a burst from concurrent listeners is serialized instead of tripping the
 * provider's limiter.
 */
public class RequestPacer {

    private final long minIntervalMillis;
    private long nextSlotAt;

    public RequestPacer(long minIntervalMillis) {
        this.minIntervalMillis = minIntervalMillis;
    }

    /**
     * Blocks until this thread's slot starts. On interrupt the interrupt flag is restored and
     * the caller proceeds; the request that follows may then fail, which callers already handle.
     */
    public void acquire() {
        long slot;
        synchronized (this) {
            slot = Math.max(System.currentTimeMillis(), nextSlotAt);
            nextSlotAt = slot + minIntervalMillis;
        }
        long wait = slot - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
