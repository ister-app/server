package app.ister.server.eventHandlers;

import app.ister.server.enums.EventType;
import app.ister.server.eventHandlers.data.MessageData;

public interface Handle<T extends MessageData> {
    org.slf4j.Logger loggerForInterface = org.slf4j.LoggerFactory.getLogger(Handle.class);

    default void listener(T t) {
        loggerForInterface.info("Received message for queue: {} and data: {}", handles(), t.toString());
        if (t.getEventType().equals(handles())) {
            handle(t);
        } else {
            loggerForInterface.error("Received message for queue: {} with wrong event type: {}", handles(), t.getEventType());
        }
    }

    EventType handles();
    Boolean handle(T messageData);
}
