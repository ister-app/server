package app.ister.core.enums;

/**
 * The kind of media item a user rating applies to. Mirrors the item types that expose a
 * {@code rating} field in the GraphQL schema.
 */
public enum RatingMediaType {
    MOVIE,
    SHOW,
    EPISODE,
    ALBUM,
    TRACK
}
