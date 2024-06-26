package app.ister.server.events.subtitlefilefound;

import app.ister.server.enums.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HandleSubtitleFileFoundTest {
    private HandleSubtitleFileFound subject;

    @BeforeEach
    void setUp() {
        subject = new HandleSubtitleFileFound();
    }

    @Test
    void handles() {
        assertEquals(EventType.SUBTITLE_FILE_FOUND, subject.handles());
    }
}