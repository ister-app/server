package app.ister.core.status;

/**
 * Lets event handlers annotate the in-flight work item that ProcessingActivityAdvice
 * registered for the current RabbitMQ delivery. Handlers run synchronously on the
 * listener thread, so a ThreadLocal scope is safe; outside a scope (tests, direct
 * calls) every method is a no-op so handlers never need to know about the registry.
 * <p>
 * {@link #subject} names the thing being worked on (a file, an episode), {@link #context}
 * names what it belongs to (the show, the album) — clients group work items on the
 * context, so every step of one media file lands under one heading. Handlers that hold
 * a {@code MediaFileEntity} should go through {@link ActivitySubjects#describe} instead
 * of spelling this out.
 */
public final class ActivityContext {

    /** Receiver for the current delivery's annotations; package-private, set by the advice. */
    interface Scope {
        void subject(String subject);

        void step(String step);

        default void context(String type, String id, String title) {
        }

        default void directory(String directory, String library) {
        }
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private ActivityContext() {
    }

    /** Reports what is being worked on (file name / entity title). No-op outside a delivery. */
    public static void subject(String subject) {
        Scope scope = CURRENT.get();
        if (scope != null) {
            scope.subject(subject);
        }
    }

    /** Reports the current sub-step as a machine token (e.g. "probe"). No-op outside a delivery. */
    public static void step(String step) {
        Scope scope = CURRENT.get();
        if (scope != null) {
            scope.step(step);
        }
    }

    /**
     * Reports the entity the work belongs to: a machine token for its kind ("show", "movie",
     * "album", "book", "podcast", "person", "library"), its id and a display title.
     */
    public static void context(String type, String id, String title) {
        Scope scope = CURRENT.get();
        if (scope != null) {
            scope.context(type, id, title);
        }
    }

    /** Reports the directory (disk) and library the work runs on; either may be null. */
    public static void directory(String directory, String library) {
        Scope scope = CURRENT.get();
        if (scope != null) {
            scope.directory(directory, library);
        }
    }

    /** Applies a described subject in one go (subject, context and directory). */
    public static void report(ActivitySubjects.Subject subject) {
        if (subject == null) {
            return;
        }
        if (subject.title() != null) {
            subject(subject.title());
        }
        if (subject.contextType() != null) {
            context(subject.contextType(), subject.contextId(), subject.context());
        }
        if (subject.directory() != null || subject.library() != null) {
            directory(subject.directory(), subject.library());
        }
    }

    static void open(Scope scope) {
        CURRENT.set(scope);
    }

    static void close() {
        CURRENT.remove();
    }
}
