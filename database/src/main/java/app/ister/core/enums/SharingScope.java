package app.ister.core.enums;

/**
 * Who may see a user's now-playing / active playback sessions.
 * <ul>
 *     <li>{@code EVERYONE} — every authenticated user (the default; preserves the original
 *     all-sessions-visible behaviour).</li>
 *     <li>{@code ALLOWLIST} — only users the owner explicitly granted the VIEW capability.</li>
 *     <li>{@code PRIVATE} — nobody but the owner.</li>
 * </ul>
 */
public enum SharingScope {
    EVERYONE,
    ALLOWLIST,
    PRIVATE
}
