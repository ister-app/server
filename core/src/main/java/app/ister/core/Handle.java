package app.ister.core;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.MessageData;

public interface Handle<T extends MessageData> {
    org.slf4j.Logger loggerForInterface = org.slf4j.LoggerFactory.getLogger(Handle.class);

    default void listener(T t) {
        loggerForInterface.debug("Received message for queue: {} and data: {}", handles(), t);
        if (t.getEventType().equals(handles())) {
            try {
                handle(t);
            } catch (RuntimeException | Error e) {
                // Log every failed attempt, not just the final one: the retry layer only surfaces
                // the last attempt's exception to the dead-letter recoverer, and that one can hide
                // the real cause — a class whose static init failed throws ExceptionInInitializerError
                // (with cause) once, then bare NoClassDefFoundError forever after.
                loggerForInterface.warn("Handler for {} failed (will retry or dead-letter)", handles(), e);
                throw e;
            }
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
