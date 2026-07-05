package app.ister.search;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SearchDocumentMapperTest {

    private final SearchDocumentMapper subject = new SearchDocumentMapper();

    private final LibraryEntity library = withId(LibraryEntity.builder().name("lib").build());

    private static <T extends app.ister.core.entity.BaseEntity> T withId(T entity) {
        entity.setId(UUID.randomUUID());
        return entity;
    }

    @Test
    void movieMapsNameYearLibraryAndPrefersEnglishMetadata() {
        MetadataEntity dutch = MetadataEntity.builder().language("nld").description("nl-beschrijving").build();
        MetadataEntity english = MetadataEntity.builder().language("eng").description("english description").genre("Drama").build();
        MovieEntity movie = withId(MovieEntity.builder()
                .name("The Matrix")
                .releaseYear(1999)
                .libraryEntity(library)
                .metadataEntities(List.of(dutch, english))
                .build());

        SearchDocument document = subject.toDocument(movie);

        assertEquals(movie.getId().toString(), document.id());
        assertEquals("MOVIE", document.type());
        assertEquals("The Matrix", document.title());
        assertEquals(1999, document.year());
        assertEquals("english description", document.description());
        assertEquals("Drama", document.genre());
        assertEquals(library.getId().toString(), document.libraryId());
    }

    @Test
    void movieWithoutMetadataHasNullDescriptionAndGenre() {
        MovieEntity movie = withId(MovieEntity.builder()
                .name("The Matrix")
                .releaseYear(1999)
                .libraryEntity(library)
                .build());

        SearchDocument document = subject.toDocument(movie);

        assertNull(document.description());
        assertNull(document.genre());
    }

    @Test
    void showMapsNameAndYear() {
        ShowEntity show = withId(ShowEntity.builder()
                .name("All in the Family")
                .releaseYear(1971)
                .libraryEntity(library)
                .build());

        SearchDocument document = subject.toDocument(show);

        assertEquals("SHOW", document.type());
        assertEquals("All in the Family", document.title());
        assertEquals(1971, document.year());
    }

    @Test
    void episodeUsesMetadataTitleAndShowContext() {
        EpisodeEntity episode = episode("Meet the Bunkers");

        SearchDocument document = subject.toDocument(episode);

        assertEquals("EPISODE", document.type());
        assertEquals("Meet the Bunkers", document.title());
        assertEquals("All in the Family", document.context());
        assertEquals(2, document.number());
        assertEquals(1, document.seasonNumber());
        assertEquals(library.getId().toString(), document.libraryId());
    }

    @Test
    void episodeWithoutMetadataTitleFallsBackToShowSeasonEpisode() {
        EpisodeEntity episode = episode(null);

        SearchDocument document = subject.toDocument(episode);

        assertEquals("All in the Family S1E2", document.title());
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
                : List.of(MetadataEntity.builder().title(metadataTitle).build());
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

        SearchDocument document = subject.toDocument(person);

        assertEquals("PERSON", document.type());
        assertEquals("Carroll O'Connor", document.title());
        assertEquals(1924, document.year());
        assertNull(document.libraryId());
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

        SearchDocument document = subject.toDocument(album);

        assertEquals("ALBUM", document.type());
        assertEquals("Abbey Road", document.title());
        assertEquals("The Beatles", document.context());
        assertEquals(1969, document.year());
    }

    @Test
    void trackUsesMetadataTitleAndArtistAlbumContext() {
        TrackEntity track = track("Come Together");

        SearchDocument document = subject.toDocument(track);

        assertEquals("TRACK", document.type());
        assertEquals("Come Together", document.title());
        assertEquals("The Beatles – Abbey Road", document.context());
        assertEquals(1, document.number());
        assertEquals(library.getId().toString(), document.libraryId());
    }

    @Test
    void trackWithoutMetadataTitleFallsBackToTrackNumber() {
        TrackEntity track = track(null);

        SearchDocument document = subject.toDocument(track);

        assertEquals("Track 1", document.title());
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
                : List.of(MetadataEntity.builder().title(metadataTitle).build());
        return withId(TrackEntity.builder()
                .personEntity(artist)
                .albumEntity(album)
                .number(1)
                .discNumber(1)
                .metadataEntities(metadata)
                .build());
    }
}
