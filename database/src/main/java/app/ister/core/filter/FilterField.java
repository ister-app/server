package app.ister.core.filter;

/**
 * The fields a custom filter can compare on. Which fields apply to which {@link FilterKind} —
 * and against which columns — is defined by {@code FilterQueryService}; this enum only carries
 * the value shape so operator validation has one home.
 */
public enum FilterField {
    /** The item's own title: the name column, or the metadata title for tracks/episodes. */
    TITLE(FilterValueType.STRING),
    ARTIST_NAME(FilterValueType.STRING),
    ALBUM_NAME(FilterValueType.STRING),
    RELEASE_YEAR(FilterValueType.NUMBER),
    BIRTH_YEAR(FilterValueType.NUMBER),
    /** Free-text metadata genre; matched over every metadata row of the item (and its parent). */
    GENRE(FilterValueType.STRING),
    /** The calling user's own 1-10 rating. */
    RATING(FilterValueType.NUMBER),
    /** How often the calling user played the track (watch-status rows). Tracks only. */
    PLAY_COUNT(FilterValueType.NUMBER),
    /** When the calling user last played the track. Tracks only. */
    LAST_PLAYED_AT(FilterValueType.DATE),
    /** Duration of the item's media file, in milliseconds. */
    DURATION(FilterValueType.NUMBER),
    /** Whether the calling user watched the item to the end. Movies and episodes. */
    WATCHED(FilterValueType.BOOLEAN),
    /** When the item was added to the library (date_created). */
    DATE_ADDED(FilterValueType.DATE);

    private final FilterValueType valueType;

    FilterField(FilterValueType valueType) {
        this.valueType = valueType;
    }

    public FilterValueType getValueType() {
        return valueType;
    }
}
