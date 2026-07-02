package app.ister.core;

/**
 * Signals that an event handler failed and the message should be retried (and eventually
 * dead-lettered). Used to wrap checked exceptions inside {@link Handle#handle}.
 */
public class EventHandlingException extends RuntimeException {

    public EventHandlingException(String message, Throwable cause) {
        super(message, cause);
    }
}
