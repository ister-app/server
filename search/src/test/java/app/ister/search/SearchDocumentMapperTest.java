package app.ister.search;

import app.ister.core.config.LanguageProperties;
import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.TrackEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SearchDocumentMapperTest {

    private final SearchDocumentMapper subject = new SearchDocumentMapper(languageProperties("en", "nl"));

    private final LibraryEntity library = withId(LibraryEntity.builder().name("lib").build());

    private static LanguageProperties languageProperties(String... tags) {
        LanguageProperties properties = new LanguageProperties();
        properties.setLanguages(List.of(tags));
        return properties;
    }

    private static <T extends app.ister.core.entity.BaseEntity> T withId(T entity) {
        entity.setId(UUID.randomUUID());
        return entity;
    }

    @Test
    void movieMapsNameYearLibraryAndIndexesEveryConfiguredLanguage() {
        MetadataEntity dutch = MetadataEntity.builder().language("nld").title("De Matrix").description("nl-beschrijving").build();
        MetadataEntity english = MetadataEntity.builder().language("eng").title("The Matrix").description("english description").genre("Drama").build();
        MovieEntity movie = withId(MovieEntity.builder()
                .name("The Matrix")
                .releaseYear(1999)
                .libraryEntity(library)
                .metadataEntities(List.of(dutch, english))
                .build());

        Map<String, Object> fields = subject.toDocument(movie).fields();

        assertEquals(movie.getId().toString(), fields.get("id"));
        assertEquals("MOVIE", fields.get("type"));
        assertEquals("The Matrix", fields.get("title"));
        assertEquals(1999, fields.get("year"));
        assertEquals(library.getId().toString(), fields.get("libraryId"));
        // Both languages are indexed into their own fields.
        assertEquals("The Matrix", fields.get("title_en"));
        assertEquals("De Matrix", fields.get("title_nl"));
        assertEquals("english description", fields.get("description_en"));
        assertEquals("nl-beschrijving", fields.get("description_nl"));
        assertEquals("Drama", fields.get("genre_en"));
        // Dutch metadata had no genre, so the field is absent.
        assertFalse(fields.containsKey("genre_nl"));
    }

    @Test
    void movieWithoutMetadataHasNoLocalizedFields() {
        MovieEntity movie = withId(MovieEntity.builder()
                .name("The Matrix")
                .releaseYear(1999)
                .libraryEntity(library)
                .build());

        Map<String, Object> fields = subject.toDocument(movie).fields();

        assertFalse(fields.containsKey("description_en"));
        assertFalse(fields.containsKey("description_nl"));
        assertFalse(fields.containsKey("genre_en"));
    }

    @Test
    void onlyConfiguredLanguagesAreIndexed() {
        SearchDocumentMapper englishOnly = new SearchDocumentMapper(languageProperties("en"));
        MetadataEntity dutch = MetadataEntity.builder().language("nld").description("nl-beschrijving").build();
        MetadataEntity english = MetadataEntity.builder().language("eng").description("english description").build();
        MovieEntity movie = withId(MovieEntity.builder()
                .name("The Matrix")
                .metadataEntities(List.of(dutch, english))
                .build());

        Map<String, Object> fields = englishOnly.toDocument(movie).fields();

        assertEquals("english description", fields.get("description_en"));
        assertFalse(fields.containsKey("description_nl"));
    }

    @Test
    void showMapsNameAndYear() {
        ShowEntity show = withId(ShowEntity.builder()
                .name("All in the Family")
                .releaseYear(1971)
                .libraryEntity(library)
                .build());

        Map<String, Object> fields = subject.toDocument(show).fields();

        assertEquals("SHOW", fields.get("type"));
        assertEquals("All in the Family", fields.get("title"));
        assertEquals(1971, fields.get("year"));
    }

    @Test
    void episodeUsesMetadataTitleAndShowContext() {
        EpisodeEntity episode = episode("Meet the Bunkers");

        Map<String, Object> fields = subject.toDocument(episode).fields();

        assertEquals("EPISODE", fields.get("type"));
        assertEquals("Meet the Bunkers", fields.get("title"));
        assertEquals("All in the Family", fields.get("context"));
        assertEquals(2, fields.get("number"));
        assertEquals(1, fields.get("seasonNumber"));
        assertEquals(library.getId().toString(), fields.get("libraryId"));
    }

    @Test
    void episodeWithoutMetadataTitleFallsBackToShowSeasonEpisode() {
        EpisodeEntity episode = episode(null);

        assertEquals("All in the Family S1E2", subject.toDocument(episode).fields().get("title"));
    }

    private EpisodeEntity episode(String metadataTitle) {
        ShowEntity show = withId(ShowEntity.builder()
                .name("All in the Family")
                .releaseYear(1971)
                .libraryEntity(library)
                .build());
        SeasonEntity season = withId(SeasonEntity.builder().showEntity(show).number(1).build());
        List<MetadataEntity> metadata = metadataTitle == null
                ? List.of()
                : List.of(MetadataEntity.builder().language("eng").title(metadataTitle).build());
        return withId(EpisodeEntity.builder()
                .showEntity(show)
                .seasonEntity(season)
                .number(2)
                .metadataEntities(metadata)
                .build());
    }

    @Test
    void personWithoutLibraryHasNullLibraryId() {
        PersonEntity person = withId(PersonEntity.builder()
                .name("Carroll O'Connor")
                .birthYear(1924)
                .build());

        Map<String, Object> fields = subject.toDocument(person).fields();

        assertEquals("PERSON", fields.get("type"));
        assertEquals("Carroll O'Connor", fields.get("title"));
        assertEquals(1924, fields.get("year"));
        assertFalse(fields.containsKey("libraryId"));
    }

    @Test
    void albumUsesArtistAsContext() {
        PersonEntity artist = withId(PersonEntity.builder().name("The Beatles").build());
        AlbumEntity album = withId(AlbumEntity.builder()
                .name("Abbey Road")
                .releaseYear(1969)
                .personEntity(artist)
                .libraryEntity(library)
                .build());

        Map<String, Object> fields = subject.toDocument(album).fields();

        assertEquals("ALBUM", fields.get("type"));
        assertEquals("Abbey Road", fields.get("title"));
        assertEquals("The Beatles", fields.get("context"));
        assertEquals(1969, fields.get("year"));
    }

    @Test
    void trackUsesMetadataTitleAndArtistAlbumContext() {
        TrackEntity track = track("Come Together");

        Map<String, Object> fields = subject.toDocument(track).fields();

        assertEquals("TRACK", fields.get("type"));
        assertEquals("Come Together", fields.get("title"));
        assertEquals("The Beatles – Abbey Road", fields.get("context"));
        assertEquals(1, fields.get("number"));
        assertEquals(library.getId().toString(), fields.get("libraryId"));
    }

    @Test
    void trackWithoutMetadataTitleFallsBackToTrackNumber() {
        TrackEntity track = track(null);

        assertEquals("Track 1", subject.toDocument(track).fields().get("title"));
    }

    private TrackEntity track(String metadataTitle) {
        PersonEntity artist = withId(PersonEntity.builder().name("The Beatles").build());
        AlbumEntity album = withId(AlbumEntity.builder()
                .name("Abbey Road")
                .releaseYear(1969)
                .personEntity(artist)
                .libraryEntity(library)
                .build());
        List<MetadataEntity> metadata = metadataTitle == null
                ? List.of()
                : List.of(MetadataEntity.builder().language("eng").title(metadataTitle).build());
        return withId(TrackEntity.builder()
                .personEntity(artist)
                .albumEntity(album)
                .number(1)
                .discNumber(1)
                .metadataEntities(metadata)
                .build());
    }
}
