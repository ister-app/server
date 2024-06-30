package app.ister.server.eventHandlers;

import app.ister.server.enums.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HandleEpisodeFoundTest {
    private HandleEpisodeFound subject;

    @BeforeEach
    void setUp() {
        subject = new HandleEpisodeFound();
    }

    @Test
    void handles() {
        assertEquals(EventType.EPISODE_FOUND, subject.handles());
    }
}