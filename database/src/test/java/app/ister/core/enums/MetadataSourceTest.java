package app.ister.core.enums;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataSourceTest {

    @Test
    void derivesFromSchemeCaseInsensitively() {
        assertEquals(Optional.of(MetadataSource.TMDB), MetadataSource.fromSourceUri("TMDB://123"));
        assertEquals(Optional.of(MetadataSource.TMDB), MetadataSource.fromSourceUri("tmdb:///poster.jpg"));
        assertEquals(Optional.of(MetadataSource.MUSICBRAINZ), MetadataSource.fromSourceUri("musicbrainz://album/Kid A"));
        assertEquals(Optional.of(MetadataSource.OPEN_LIBRARY), MetadataSource.fromSourceUri("OpenLibrary://works/OL1W"));
        assertEquals(Optional.of(MetadataSource.WIKIPEDIA), MetadataSource.fromSourceUri("wikipedia://Some Series"));
        assertEquals(Optional.of(MetadataSource.WIKIDATA), MetadataSource.fromSourceUri("wikidata://Q42"));
        assertEquals(Optional.of(MetadataSource.PODCAST_FEED), MetadataSource.fromSourceUri("feed://https://example.org/rss"));
        assertEquals(Optional.of(MetadataSource.LOCAL_FILE), MetadataSource.fromSourceUri("file:///data/movie.nfo"));
    }

    @Test
    void hostOverridesSchemeForCoverArtAndWikimediaDownloads() {
        assertEquals(Optional.of(MetadataSource.COVER_ART_ARCHIVE),
                MetadataSource.fromSourceUri("MusicBrainz://https://coverartarchive.org/release-group/x/front"));
        assertEquals(Optional.of(MetadataSource.WIKIMEDIA_COMMONS),
                MetadataSource.fromSourceUri("MusicBrainz://https://commons.wikimedia.org/wiki/Special:FilePath/a.jpg"));
        assertEquals(Optional.of(MetadataSource.WIKIMEDIA_COMMONS),
                MetadataSource.fromSourceUri("wikipedia://https://upload.wikimedia.org/artist.jpg"));
    }

    @Test
    void unknownOrMalformedUrisDeriveNothing() {
        assertTrue(MetadataSource.fromSourceUri(null).isEmpty());
        assertTrue(MetadataSource.fromSourceUri("").isEmpty());
        assertTrue(MetadataSource.fromSourceUri("no-scheme-here").isEmpty());
        assertTrue(MetadataSource.fromSourceUri("comicvine://4050-1234").isEmpty());
    }
}
