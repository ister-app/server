package app.ister.core.enums;

/**
 * Who may remote-control a user's playback session. Used both as the account-level default and as
 * a per-session override (stored on the play queue).
 * <ul>
 *     <li>{@code PRIVATE} — nobody but the owner (the default).</li>
 *     <li>{@code EVERYONE} — every authenticated user.</li>
 *     <li>{@code ALLOWLIST} — only users granted the CONTROL capability (account-level list), or,
 *     for a per-session override, the session's own control allowlist.</li>
 *     <li>{@code SAME_AS_NOW_PLAYING} — mirror the now-playing {@link SharingScope} and its VIEW
 *     allowlist. Not a valid per-session override value in isolation; the resolver delegates to the
 *     now-playing decision.</li>
 * </ul>
 */
public enum RemoteControlScope {
    PRIVATE,
    EVERYONE,
    ALLOWLIST,
    SAME_AS_NOW_PLAYING
}
