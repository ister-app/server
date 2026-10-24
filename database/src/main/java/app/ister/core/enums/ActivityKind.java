package app.ister.core.enums;

/**
 * What kind of session a now-playing entry describes. PLAYBACK is a play-queue heartbeat
 * (video, music, audiobook, podcast); READING and GAME are activity sessions reported by
 * the reader and the games page, which have no play queue and cannot be controlled or followed.
 */
public enum ActivityKind {
    PLAYBACK,
    READING,
    GAME
}
