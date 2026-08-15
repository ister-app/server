package app.ister.worker.http;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestPacerTest {

    @Test
    void spacesConcurrentAcquiresByTheMinimumInterval() throws InterruptedException {
        RequestPacer pacer = new RequestPacer(100);
        List<Long> timestamps = java.util.Collections.synchronizedList(new ArrayList<>());

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                pacer.acquire();
                timestamps.add(System.currentTimeMillis());
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(4, timestamps.size());
        timestamps.sort(Long::compare);
        for (int i = 1; i < timestamps.size(); i++) {
            long gap = timestamps.get(i) - timestamps.get(i - 1);
            assertTrue(gap >= 80, "acquires " + (i - 1) + " and " + i + " only " + gap + "ms apart");
        }
    }

    @Test
    void zeroIntervalDoesNotBlock() {
        RequestPacer pacer = new RequestPacer(0);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            pacer.acquire();
        }
        assertTrue(System.currentTimeMillis() - start < 1000);
    }
}
