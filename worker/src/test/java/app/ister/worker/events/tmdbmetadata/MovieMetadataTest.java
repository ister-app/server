package app.ister.worker.events.tmdbmetadata;

import app.ister.tmdbapi.model.MovieDetails200Response;
import app.ister.tmdbapi.model.MovieDetails200ResponseGenresInner;
import app.ister.tmdbapi.model.MovieDetails200ResponseProductionCompaniesInner;
import app.ister.tmdbapi.model.MovieDetails200ResponseProductionCountriesInner;
import app.ister.tmdbapi.model.SearchMovie200Response;
import app.ister.tmdbapi.model.SearchMovie200ResponseResultsInner;
import app.ister.worker.clients.TmdbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import app.ister.core.config.LanguageProperties;

@ExtendWith(MockitoExtension.class)
class MovieMetadataTest {

    @Mock
    private TmdbClient tmdbClientMock;
    @Mock
    private SearchMovie200Response searchResponseMock;
    @Mock
    private SearchMovie200ResponseResultsInner resultInnerMock;
    @Mock
    private MovieDetails200Response movieDetailsMock;

    private MovieMetadata subject;

    @BeforeEach
    void setUp() {
        subject = new MovieMetadata(tmdbClientMock, new TmdbSearchService(tmdbClientMock, new TmdbResultSelector(), new LanguageProperties()), new TmdbImageBase("https://image.tmdb.org/t/p/original"));
    }

    @Test
    void happyFlow() {
        when(tmdbClientMock._searchMovie("Movie", null, null, "2024", null, null, null))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of(resultInnerMock));
        when(resultInnerMock.getId()).thenReturn(99);
        when(tmdbClientMock._movieDetails(99, "", "en"))
                .thenReturn(ResponseEntity.ok(movieDetailsMock));
        when(movieDetailsMock.getReleaseDate()).thenReturn("2024-06-15");
        when(movieDetailsMock.getOverview()).thenReturn("A great movie");
        when(movieDetailsMock.getTitle()).thenReturn("Movie");
        when(movieDetailsMock.getId()).thenReturn(99);
        when(movieDetailsMock.getPosterPath()).thenReturn("/poster.jpg");
        when(movieDetailsMock.getBackdropPath()).thenReturn("/backdrop.jpg");

        Optional<TMDBResult> result = subject.getMetadata("Movie", 2024, "en");

        assertTrue(result.isPresent());
        assertEquals("eng", result.get().getLanguage());
        assertEquals("Movie", result.get().getTitle());
        assertEquals("A great movie", result.get().getDescription());
        assertEquals("https://image.tmdb.org/t/p/original/poster.jpg", result.get().getPosterUrl());
        assertEquals("https://image.tmdb.org/t/p/original/backdrop.jpg", result.get().getBackgroundUrl());
    }

    @Test
    void happyFlowWithNullPaths() {
        when(tmdbClientMock._searchMovie("Movie", null, null, "2024", null, null, null))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of(resultInnerMock));
        when(resultInnerMock.getId()).thenReturn(1);
        when(tmdbClientMock._movieDetails(1, "", "en"))
                .thenReturn(ResponseEntity.ok(movieDetailsMock));
        when(movieDetailsMock.getReleaseDate()).thenReturn("2024-01-01");
        when(movieDetailsMock.getOverview()).thenReturn("overview");
        when(movieDetailsMock.getTitle()).thenReturn("Movie");
        when(movieDetailsMock.getId()).thenReturn(1);
        when(movieDetailsMock.getPosterPath()).thenReturn(null);
        when(movieDetailsMock.getBackdropPath()).thenReturn(null);

        Optional<TMDBResult> result = subject.getMetadata("Movie", 2024, "en");

        assertTrue(result.isPresent());
        assertNull(result.get().getPosterUrl());
        assertNull(result.get().getBackgroundUrl());
    }

    @Test
    void mapsEnrichmentFields() {
        when(tmdbClientMock._searchMovie("Movie", null, null, "2024", null, null, null))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of(resultInnerMock));
        when(resultInnerMock.getId()).thenReturn(99);
        when(tmdbClientMock._movieDetails(99, "", "en"))
                .thenReturn(ResponseEntity.ok(movieDetailsMock));
        when(movieDetailsMock.getReleaseDate()).thenReturn("2024-06-15");
        when(movieDetailsMock.getOverview()).thenReturn("A great movie");
        when(movieDetailsMock.getTitle()).thenReturn("Movie");
        when(movieDetailsMock.getId()).thenReturn(99);
        when(movieDetailsMock.getTagline()).thenReturn("One tagline to rule them all");
        when(movieDetailsMock.getGenres()).thenReturn(List.of(
                new MovieDetails200ResponseGenresInner().id(878).name("Science Fiction"),
                new MovieDetails200ResponseGenresInner().id(28).name("Action")));
        when(movieDetailsMock.getRuntime()).thenReturn(138);
        when(movieDetailsMock.getVoteAverage()).thenReturn(new BigDecimal("7.8"));
        when(movieDetailsMock.getVoteCount()).thenReturn(1234);
        when(movieDetailsMock.getStatus()).thenReturn("Released");
        when(movieDetailsMock.getHomepage()).thenReturn("https://example.com");
        when(movieDetailsMock.getImdbId()).thenReturn("tt1234567");
        when(movieDetailsMock.getBelongsToCollection()).thenReturn(Map.of("id", 10, "name", "The Movie Collection"));
        when(movieDetailsMock.getProductionCompanies()).thenReturn(List.of(
                new MovieDetails200ResponseProductionCompaniesInner().name("Warner Bros. Pictures")));
        when(movieDetailsMock.getProductionCountries()).thenReturn(List.of(
                new MovieDetails200ResponseProductionCountriesInner().iso31661("US")));

        TMDBResult result = subject.getMetadata("Movie", 2024, "en").orElseThrow();

        assertEquals("One tagline to rule them all", result.getTagline());
        assertEquals("Science Fiction, Action", result.getGenres());
        assertEquals(138, result.getRuntime());
        assertEquals(new BigDecimal("7.8"), result.getVoteAverage());
        assertEquals(1234, result.getVoteCount());
        assertEquals("Released", result.getStatus());
        assertEquals("https://example.com", result.getHomepage());
        assertEquals("tt1234567", result.getImdbId());
        assertEquals(10, result.getCollectionTmdbId());
        assertEquals("The Movie Collection", result.getCollectionName());
        assertEquals("Warner Bros. Pictures", result.getStudios());
        assertEquals("US", result.getOriginCountry());
    }

    @Test
    void enrichmentFieldsNullSafe() {
        when(tmdbClientMock._searchMovie("Movie", null, null, "2024", null, null, null))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of(resultInnerMock));
        when(resultInnerMock.getId()).thenReturn(99);
        when(tmdbClientMock._movieDetails(99, "", "en"))
                .thenReturn(ResponseEntity.ok(movieDetailsMock));
        when(movieDetailsMock.getReleaseDate()).thenReturn("2024-06-15");
        when(movieDetailsMock.getOverview()).thenReturn("A great movie");
        when(movieDetailsMock.getTagline()).thenReturn("  ");
        when(movieDetailsMock.getRuntime()).thenReturn(0);

        TMDBResult result = subject.getMetadata("Movie", 2024, "en").orElseThrow();

        assertNull(result.getTagline());
        assertNull(result.getGenres());
        assertNull(result.getRuntime());
        assertNull(result.getCollectionTmdbId());
        assertNull(result.getCollectionName());
    }

    @Test
    void returnsEmptyWhenNoSearchResults() {
        when(tmdbClientMock._searchMovie("Movie", null, null, "2024", null, null, null))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of());

        assertTrue(subject.getMetadata("Movie", 2024, "en").isEmpty());
    }

    @Test
    void returnsEmptyWhenNullSearchResponse() {
        when(tmdbClientMock._searchMovie("Movie", null, null, "2024", null, null, null))
                .thenReturn(ResponseEntity.ok(null));

        assertTrue(subject.getMetadata("Movie", 2024, "en").isEmpty());
    }

    @Test
    void returnsEmptyWhenMovieDetailsMissingRequiredFields() {
        when(tmdbClientMock._searchMovie("Movie", null, null, "2024", null, null, null))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of(resultInnerMock));
        when(resultInnerMock.getId()).thenReturn(1);
        when(tmdbClientMock._movieDetails(1, "", "en"))
                .thenReturn(ResponseEntity.ok(movieDetailsMock));
        when(movieDetailsMock.getReleaseDate()).thenReturn(null);

        assertTrue(subject.getMetadata("Movie", 2024, "en").isEmpty());
    }


    @Test
    void fallsBackToEnglishTextsWhenTheLanguageHasNoTranslation() {
        MovieDetails200Response englishDetails = org.mockito.Mockito.mock(MovieDetails200Response.class);
        when(tmdbClientMock._searchMovie("Your Name", null, null, "2016", null, null, null))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of(resultInnerMock));
        when(resultInnerMock.getId()).thenReturn(372058);
        when(tmdbClientMock._movieDetails(372058, "", "nl")).thenReturn(ResponseEntity.ok(movieDetailsMock));
        when(movieDetailsMock.getTitle()).thenReturn("君の名は。");
        when(movieDetailsMock.getOriginalTitle()).thenReturn("君の名は。");
        when(movieDetailsMock.getOriginalLanguage()).thenReturn("ja");
        when(movieDetailsMock.getOverview()).thenReturn("");
        when(movieDetailsMock.getReleaseDate()).thenReturn("2016-08-26");
        when(movieDetailsMock.getId()).thenReturn(372058);
        when(tmdbClientMock._movieDetails(372058, "", "en")).thenReturn(ResponseEntity.ok(englishDetails));
        when(englishDetails.getTitle()).thenReturn("Your Name.");
        when(englishDetails.getOverview()).thenReturn("Two strangers swap bodies.");

        Optional<TMDBResult> result = subject.getMetadata("Your Name", 2016, "nl");

        assertTrue(result.isPresent());
        assertEquals("nld", result.get().getLanguage());
        assertEquals("Your Name.", result.get().getTitle());
        assertEquals("Two strangers swap bodies.", result.get().getDescription());
    }


    /** "V for Vendetta (2005)": the year filter finds nothing, the year-less search does, one year off. */
    @Test
    void findsAMovieWhoseDirectoryYearIsOffByOne() {
        SearchMovie200Response anyYearResponse = org.mockito.Mockito.mock(SearchMovie200Response.class);
        SearchMovie200ResponseResultsInner film = new SearchMovie200ResponseResultsInner();
        film.setId(752); film.setTitle("V for Vendetta"); film.setReleaseDate("2006-02-23"); film.setPopularity(new BigDecimal("40"));
        SearchMovie200ResponseResultsInner extra = new SearchMovie200ResponseResultsInner();
        extra.setId(753); extra.setTitle("V for Vendetta: Unmasked"); extra.setReleaseDate("2006-05-01"); extra.setPopularity(new BigDecimal("50"));
        when(tmdbClientMock._searchMovie("V for Vendetta", null, null, "2005", null, null, null))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of());
        when(tmdbClientMock._searchMovie("V for Vendetta", null, "nl", "2005", null, null, null))
                .thenReturn(ResponseEntity.ok(null));
        when(tmdbClientMock._searchMovie("V for Vendetta", null, null, null, null, null, null))
                .thenReturn(ResponseEntity.ok(anyYearResponse));
        when(anyYearResponse.getResults()).thenReturn(List.of(film, extra));
        when(tmdbClientMock._movieDetails(752, "", "en")).thenReturn(ResponseEntity.ok(movieDetailsMock));
        when(movieDetailsMock.getReleaseDate()).thenReturn("2006-02-23");
        when(movieDetailsMock.getOverview()).thenReturn("Remember, remember.");
        when(movieDetailsMock.getTitle()).thenReturn("V for Vendetta");
        when(movieDetailsMock.getId()).thenReturn(752);

        Optional<TMDBResult> result = subject.getMetadata("V for Vendetta", 2005, "en");

        assertEquals(752, result.orElseThrow().getTmdbId());
    }

    /** "300 (2006)": the year filter returns unrelated titles; without an exact match nearby, no metadata. */
    @Test
    void doesNotPickAnUnrelatedTitleForAWrongYear() {
        SearchMovie200Response anyYearResponse = org.mockito.Mockito.mock(SearchMovie200Response.class);
        SearchMovie200ResponseResultsInner junk1 = new SearchMovie200ResponseResultsInner();
        junk1.setId(1); junk1.setTitle("Home Movies 300-1"); junk1.setPopularity(new BigDecimal("2"));
        SearchMovie200ResponseResultsInner junk2 = new SearchMovie200ResponseResultsInner();
        junk2.setId(2); junk2.setTitle("Rob-B-Hood"); junk2.setPopularity(new BigDecimal("9"));
        SearchMovie200ResponseResultsInner farOff = new SearchMovie200ResponseResultsInner();
        farOff.setId(3); farOff.setTitle("300"); farOff.setReleaseDate("1962-01-01"); farOff.setPopularity(new BigDecimal("9"));
        when(tmdbClientMock._searchMovie("300", null, null, "2006", null, null, null))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of(junk1, junk2));
        when(tmdbClientMock._searchMovie("300", null, "nl", "2006", null, null, null))
                .thenReturn(ResponseEntity.ok(null));
        when(tmdbClientMock._searchMovie("300", null, null, null, null, null, null))
                .thenReturn(ResponseEntity.ok(anyYearResponse));
        when(anyYearResponse.getResults()).thenReturn(List.of(farOff));

        assertTrue(subject.getMetadata("300", 2006, "en").isEmpty());
        org.mockito.Mockito.verify(tmdbClientMock, org.mockito.Mockito.never())._movieDetails(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    /** "De Smurfen (2011)": no English match, but the Dutch search has the exact title. */
    @Test
    void matchesADirectoryNamedInAConfiguredLanguage() {
        SearchMovie200Response dutchResponse = org.mockito.Mockito.mock(SearchMovie200Response.class);
        SearchMovie200ResponseResultsInner film = new SearchMovie200ResponseResultsInner();
        film.setId(41513); film.setTitle("De Smurfen"); film.setOriginalTitle("The Smurfs"); film.setPopularity(new BigDecimal("30"));
        SearchMovie200ResponseResultsInner english = new SearchMovie200ResponseResultsInner();
        english.setId(41513); english.setTitle("The Smurfs"); english.setOriginalTitle("The Smurfs"); english.setPopularity(new BigDecimal("30"));
        SearchMovie200ResponseResultsInner other = new SearchMovie200ResponseResultsInner();
        other.setId(5); other.setTitle("Smurfs: The Lost Village"); other.setPopularity(new BigDecimal("31"));
        when(tmdbClientMock._searchMovie("De Smurfen", null, null, "2011", null, null, null))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of(english, other));
        when(tmdbClientMock._searchMovie("De Smurfen", null, "nl", "2011", null, null, null))
                .thenReturn(ResponseEntity.ok(dutchResponse));
        when(dutchResponse.getResults()).thenReturn(List.of(film, other));
        when(tmdbClientMock._movieDetails(41513, "", "en")).thenReturn(ResponseEntity.ok(movieDetailsMock));
        when(movieDetailsMock.getReleaseDate()).thenReturn("2011-07-29");
        when(movieDetailsMock.getOverview()).thenReturn("Blue.");
        when(movieDetailsMock.getTitle()).thenReturn("The Smurfs");
        when(movieDetailsMock.getId()).thenReturn(41513);

        assertEquals(41513, subject.getMetadata("De Smurfen", 2011, "en").orElseThrow().getTmdbId());
    }
}
