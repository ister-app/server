package app.ister.core.enums;

/**
 * Result of the followPlayQueue mutation. NOT_FOUND covers both a missing queue/session and
 * missing control permission (deny-as-not-found); NO_LIBRARY_ACCESS is only distinguished after
 * the caller has proven control rights, at which point the session's existence is already
 * legitimately visible to them via now-playing.
 */
public enum FollowResult {
    OK,
    NOT_FOUND,
    NO_LIBRARY_ACCESS
}
