package app.ister.server.eventHandlers;

import app.ister.server.enums.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HandleNewDirectoriesScanRequestedTest {
    private HandleNewDirectoriesScanRequested subject;

    @BeforeEach
    void setUp() {
        subject = new HandleNewDirectoriesScanRequested();
    }

    @Test
    void handles() {
        assertEquals(EventType.NEW_DIRECTORIES_SCAN_REQUEST, subject.handles());
    }
}