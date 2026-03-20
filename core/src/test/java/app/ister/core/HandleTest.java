package app.ister.core;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.MessageData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HandleTest {

    private static class TestHandle implements Handle<MessageData> {
        @Override
        public EventType handles() {
            return EventType.SHOW_FOUND;
        }

        @Override
        public Boolean handle(MessageData messageData) {
            return true;
        }
    }

    @Test
    void listenerCallsHandleWhenEventTypeMatches() {
        TestHandle subject = new TestHandle();
        MessageData data = MessageData.builder()
                .eventType(EventType.SHOW_FOUND)
                .build();
        assertDoesNotThrow(() -> subject.listener(data));
    }

    @Test
    void listenerThrowsWhenEventTypeDoesNotMatch() {
        TestHandle subject = new TestHandle();
        MessageData data = MessageData.builder()
                .eventType(EventType.EPISODE_FOUND)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }
}
