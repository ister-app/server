package app.ister.server.eventHandlers;

import app.ister.server.enums.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HandleShowFoundTest {
    private HandleShowFound subject;

    @BeforeEach
    void setUp() {
        subject = new HandleShowFound();
    }

    @Test
    void handles() {
        assertEquals(EventType.SHOW_FOUND, subject.handles());
    }
}