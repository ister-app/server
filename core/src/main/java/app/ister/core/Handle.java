package app.ister.core;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.MessageData;

public interface Handle<T extends MessageData> {
    org.slf4j.Logger loggerForInterface = org.slf4j.LoggerFactory.getLogger(Handle.class);

    default void listener(T t) {
        loggerForInterface.debug("Received message for queue: {} and data: {}", handles(), t);
        if (t.getEventType().equals(handles())) {
            handle(t);
        } else {
            loggerForInterface.error("Received message for queue: {} with wrong event type: {}", handles(), t.getEventType());
            throw new IllegalArgumentException("Expected event type " + handles() + " but got " + t.getEventType());
        }
    }

    EventType handles();

    /**
     * Processes the event. Failures must be signaled by throwing an exception (wrap checked
     * exceptions in {@link EventHandlingException}): the message is then retried with backoff
     * and, once retries are exhausted, republished to the dead-letter queue for inspection.
     * Returning normally acknowledges the message.
     */
    void handle(T messageData);
}
