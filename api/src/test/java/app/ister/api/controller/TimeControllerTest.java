package app.ister.api.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeControllerTest {

    @Test
    void returnsTheCurrentServerClock() {
        long before = System.currentTimeMillis();
        long reported = new TimeController().time().get("serverTimeMs");
        long after = System.currentTimeMillis();

        assertTrue(reported >= before && reported <= after);
    }
}
