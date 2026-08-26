package app.ister.core.enums;

/**
 * The container a track playback history is asked for: every track on an album, or every track an
 * artist is credited on. Deliberately separate from {@link MediaType}, which addresses playable
 * items (play queues, continue watching) — a container is not one of those.
 */
public enum TrackHistoryScope {
    ALBUM,
    ARTIST
}
