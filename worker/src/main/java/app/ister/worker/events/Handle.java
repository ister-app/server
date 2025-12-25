package app.ister.worker.events;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.MessageData;

public interface Handle<T extends MessageData> {
    org.slf4j.Logger loggerForInterface = org.slf4j.LoggerFactory.getLogger(Handle.class);

    default void listener(T t) {
        loggerForInterface.info("Received message for queue: {} and data: {}", handles(), t);
        if (t.getEventType().equals(handles())) {
            handle(t);
        } else {
            loggerForInterface.error("Received message for queue: {} with wrong event type: {}", handles(), t.getEventType());
            throw new IllegalArgumentException();
        }
    }

    EventType handles();

    Boolean handle(T messageData);
}
