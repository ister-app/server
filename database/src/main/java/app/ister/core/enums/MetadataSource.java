package app.ister.core.enums;

import lombok.Getter;

import java.util.Locale;
import java.util.Optional;

/**
 * The external provider a metadata row or image originated from, normalized from the
 * free-form sourceUri scheme (e.g. "TMDB://...", "musicbrainz://..."). Used for
 * attribution in clients; sourceUri itself stays the provenance/dedup key.
 */
@Getter
public enum MetadataSource {
    TMDB("tmdb", "TMDB", "https://www.themoviedb.org"),
    MUSICBRAINZ("musicbrainz", "MusicBrainz", "https://musicbrainz.org"),
    COVER_ART_ARCHIVE(null, "Cover Art Archive", "https://coverartarchive.org"),
    WIKIMEDIA_COMMONS(null, "Wikimedia Commons", "https://commons.wikimedia.org"),
    WIKIPEDIA("wikipedia", "Wikipedia", "https://www.wikipedia.org"),
    WIKIDATA("wikidata", "Wikidata", "https://www.wikidata.org"),
    OPEN_LIBRARY("openlibrary", "Open Library", "https://openlibrary.org"),
    PODCAST_FEED("feed", "Podcast feed", null),
    LOCAL_FILE("file", "Local file", null);

    private final String scheme;
    private final String displayName;
    private final String url;

    MetadataSource(String scheme, String displayName, String url) {
        this.scheme = scheme;
        this.displayName = displayName;
        this.url = url;
    }

    /**
     * Derive the source from a sourceUri. The scheme decides, except that images fetched
     * from the Cover Art Archive or Wikimedia carry a full download URL after the scheme
     * ("MusicBrainz://https://coverartarchive.org/...", "wikipedia://https://upload.wikimedia.org/...")
     * whose host names the actual origin.
     */
    public static Optional<MetadataSource> fromSourceUri(String sourceUri) {
        if (sourceUri == null) {
            return Optional.empty();
        }
        int schemeEnd = sourceUri.indexOf("://");
        if (schemeEnd < 0) {
            return Optional.empty();
        }
        String lower = sourceUri.toLowerCase(Locale.ROOT);
        String rest = lower.substring(schemeEnd + 3);
        if (rest.contains("coverartarchive.org")) {
            return Optional.of(COVER_ART_ARCHIVE);
        }
        if (rest.contains("wikimedia.org")) {
            return Optional.of(WIKIMEDIA_COMMONS);
        }
        String scheme = lower.substring(0, schemeEnd);
        for (MetadataSource source : values()) {
            if (scheme.equals(source.scheme)) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }
}
