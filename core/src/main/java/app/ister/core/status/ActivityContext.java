package app.ister.core.status;

/**
 * Lets event handlers annotate the in-flight work item that ProcessingActivityAdvice
 * registered for the current RabbitMQ delivery. Handlers run synchronously on the
 * listener thread, so a ThreadLocal scope is safe; outside a scope (tests, direct
 * calls) both methods are no-ops so handlers never need to know about the registry.
 */
public final class ActivityContext {

    /** Receiver for the current delivery's annotations; package-private, set by the advice. */
    interface Scope {
        void subject(String subject);

        void step(String step);
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

    static void open(Scope scope) {
        CURRENT.set(scope);
    }

    static void close() {
        CURRENT.remove();
    }
}
