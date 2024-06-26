package app.ister.server.events.nfofilefound;

import app.ister.server.enums.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HandleNfoFileFoundTest {
    private HandleNfoFileFound subject;

    @BeforeEach
    void setUp() {
        subject = new HandleNfoFileFound();
    }

    @Test
    void handles() {
        assertEquals(EventType.NFO_FILE_FOUND, subject.handles());
    }
}