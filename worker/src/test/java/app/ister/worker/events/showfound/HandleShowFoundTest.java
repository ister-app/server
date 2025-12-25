package app.ister.worker.events.showfound;

import app.ister.core.enums.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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