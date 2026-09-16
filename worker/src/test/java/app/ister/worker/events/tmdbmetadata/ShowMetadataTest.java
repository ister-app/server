package app.ister.worker.events.tmdbmetadata;

import app.ister.tmdbapi.model.SearchTv200Response;
import app.ister.tmdbapi.model.SearchTv200ResponseResultsInner;
import app.ister.tmdbapi.model.TvSeriesDetails200Response;
import app.ister.tmdbapi.model.TvSeriesDetails200ResponseGenresInner;
import app.ister.tmdbapi.model.TvSeriesDetails200ResponseNetworksInner;
import app.ister.tmdbapi.model.TvSeriesDetails200ResponseProductionCompaniesInner;
import app.ister.worker.clients.TmdbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import app.ister.core.config.LanguageProperties;

@ExtendWith(MockitoExtension.class)
class ShowMetadataTest {

    @Mock
    private TmdbClient tmdbClientMock;
    @Mock
    private SearchTv200Response searchResponseMock;
    @Mock
    private SearchTv200ResponseResultsInner resultInnerMock;
    @Mock
    private TvSeriesDetails200Response tvSeriesDetailsMock;

    private ShowMetadata subject;

    @BeforeEach
    void setUp() {
        subject = new ShowMetadata(tmdbClientMock, new TmdbSearchService(tmdbClientMock, new TmdbResultSelector(), new LanguageProperties()), new TmdbImageBase("https://image.tmdb.org/t/p/original"));
    }

    @Test
    void happyFlow() {
        when(tmdbClientMock._searchTv("Show", null, null, null, null, 2024))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of(resultInnerMock));
        when(resultInnerMock.getId()).thenReturn(42);
        when(tmdbClientMock._tvSeriesDetails(42, "", "en"))
                .thenReturn(ResponseEntity.ok(tvSeriesDetailsMock));
        when(tvSeriesDetailsMock.getFirstAirDate()).thenReturn("2024-03-01");
        when(tvSeriesDetailsMock.getOverview()).thenReturn("A great show");
        when(tvSeriesDetailsMock.getName()).thenReturn("Show");
        when(tvSeriesDetailsMock.getId()).thenReturn(42);
        when(tvSeriesDetailsMock.getPosterPath()).thenReturn("/poster.jpg");
        when(tvSeriesDetailsMock.getBackdropPath()).thenReturn("/backdrop.jpg");

        Optional<TMDBResult> result = subject.getMetadata("Show", 2024, "en");

        assertTrue(result.isPresent());
        assertEquals("eng", result.get().getLanguage());
        assertEquals("Show", result.get().getTitle());
        assertEquals("A great show", result.get().getDescription());
        assertEquals("https://image.tmdb.org/t/p/original/poster.jpg", result.get().getPosterUrl());
        assertEquals("https://image.tmdb.org/t/p/original/backdrop.jpg", result.get().getBackgroundUrl());
    }

    @Test
    void mapsEnrichmentFields() {
        when(tmdbClientMock._searchTv("Show", null, null, null, null, 2024))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of(resultInnerMock));
        when(resultInnerMock.getId()).thenReturn(42);
        when(tmdbClientMock._tvSeriesDetails(42, "", "en"))
                .thenReturn(ResponseEntity.ok(tvSeriesDetailsMock));
        when(tvSeriesDetailsMock.getFirstAirDate()).thenReturn("2024-03-01");
        when(tvSeriesDetailsMock.getOverview()).thenReturn("A great show");
        when(tvSeriesDetailsMock.getName()).thenReturn("Show");
        when(tvSeriesDetailsMock.getId()).thenReturn(42);
        when(tvSeriesDetailsMock.getTagline()).thenReturn("Winter is coming");
        when(tvSeriesDetailsMock.getGenres()).thenReturn(List.of(
                new TvSeriesDetails200ResponseGenresInner().name("Drama"),
                new TvSeriesDetails200ResponseGenresInner().name("Fantasy")));
        when(tvSeriesDetailsMock.getVoteAverage()).thenReturn(new BigDecimal("8.4"));
        when(tvSeriesDetailsMock.getVoteCount()).thenReturn(24000);
        when(tvSeriesDetailsMock.getStatus()).thenReturn("Ended");
        when(tvSeriesDetailsMock.getHomepage()).thenReturn("https://example.com");
        when(tvSeriesDetailsMock.getNetworks()).thenReturn(List.of(
                new TvSeriesDetails200ResponseNetworksInner().name("HBO")));
        when(tvSeriesDetailsMock.getProductionCompanies()).thenReturn(List.of(
                new TvSeriesDetails200ResponseProductionCompaniesInner().name("Revolution Sun Studios")));
        when(tvSeriesDetailsMock.getOriginCountry()).thenReturn(List.of("US", "GB"));

        TMDBResult result = subject.getMetadata("Show", 2024, "en").orElseThrow();

        assertEquals("Winter is coming", result.getTagline());
        assertEquals("Drama, Fantasy", result.getGenres());
        assertEquals(new BigDecimal("8.4"), result.getVoteAverage());
        assertEquals(24000, result.getVoteCount());
        assertEquals("Ended", result.getStatus());
        assertEquals("https://example.com", result.getHomepage());
        assertEquals("HBO", result.getNetworks());
        assertEquals("Revolution Sun Studios", result.getStudios());
        assertEquals("US, GB", result.getOriginCountry());
    }

    @Test
    void happyFlowWithNullPaths() {
        when(tmdbClientMock._searchTv("Show", null, null, null, null, 2024))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of(resultInnerMock));
        when(resultInnerMock.getId()).thenReturn(1);
        when(tmdbClientMock._tvSeriesDetails(1, "", "en"))
                .thenReturn(ResponseEntity.ok(tvSeriesDetailsMock));
        when(tvSeriesDetailsMock.getFirstAirDate()).thenReturn("2024-01-01");
        when(tvSeriesDetailsMock.getOverview()).thenReturn("overview");
        when(tvSeriesDetailsMock.getName()).thenReturn("Show");
        when(tvSeriesDetailsMock.getId()).thenReturn(1);
        when(tvSeriesDetailsMock.getPosterPath()).thenReturn(null);
        when(tvSeriesDetailsMock.getBackdropPath()).thenReturn(null);

        Optional<TMDBResult> result = subject.getMetadata("Show", 2024, "en");

        assertTrue(result.isPresent());
        assertNull(result.get().getPosterUrl());
        assertNull(result.get().getBackgroundUrl());
    }

    @Test
    void returnsEmptyWhenNoSearchResults() {
        when(tmdbClientMock._searchTv("Show", null, null, null, null, 2024))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of());

        assertTrue(subject.getMetadata("Show", 2024, "en").isEmpty());
    }

    @Test
    void returnsEmptyWhenNullSearchResponse() {
        when(tmdbClientMock._searchTv("Show", null, null, null, null, 2024))
                .thenReturn(ResponseEntity.ok(null));

        assertTrue(subject.getMetadata("Show", 2024, "en").isEmpty());
    }

    @Test
    void returnsEmptyWhenSeriesDetailsMissingRequiredFields() {
        when(tmdbClientMock._searchTv("Show", null, null, null, null, 2024))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of(resultInnerMock));
        when(resultInnerMock.getId()).thenReturn(1);
        when(tmdbClientMock._tvSeriesDetails(1, "", "en"))
                .thenReturn(ResponseEntity.ok(tvSeriesDetailsMock));
        when(tvSeriesDetailsMock.getFirstAirDate()).thenReturn(null);

        assertTrue(subject.getMetadata("Show", 2024, "en").isEmpty());
    }


    @Test
    void fallsBackToEnglishTextsWhenTheLanguageHasNoTranslation() {
        TvSeriesDetails200Response englishDetails = org.mockito.Mockito.mock(TvSeriesDetails200Response.class);
        when(tmdbClientMock._searchTv("Death Note", null, null, null, null, 2006))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of(resultInnerMock));
        when(resultInnerMock.getId()).thenReturn(13916);
        when(tmdbClientMock._tvSeriesDetails(13916, "", "nl")).thenReturn(ResponseEntity.ok(tvSeriesDetailsMock));
        when(tvSeriesDetailsMock.getName()).thenReturn("デスノート");
        when(tvSeriesDetailsMock.getOriginalName()).thenReturn("デスノート");
        when(tvSeriesDetailsMock.getOriginalLanguage()).thenReturn("ja");
        when(tvSeriesDetailsMock.getOverview()).thenReturn("");
        when(tvSeriesDetailsMock.getFirstAirDate()).thenReturn("2006-10-04");
        when(tvSeriesDetailsMock.getId()).thenReturn(13916);
        when(tmdbClientMock._tvSeriesDetails(13916, "", "en")).thenReturn(ResponseEntity.ok(englishDetails));
        when(englishDetails.getName()).thenReturn("Death Note");
        when(englishDetails.getOverview()).thenReturn("A notebook that kills.");

        Optional<TMDBResult> result = subject.getMetadata("Death Note", 2006, "nl");

        assertTrue(result.isPresent());
        assertEquals("nld", result.get().getLanguage());
        assertEquals("Death Note", result.get().getTitle());
        assertEquals("A notebook that kills.", result.get().getDescription());
    }

    @Test
    void keepsATranslatedTitleWithoutASecondCall() {
        when(tmdbClientMock._searchTv("Show", null, null, null, null, 2024))
                .thenReturn(ResponseEntity.ok(searchResponseMock));
        when(searchResponseMock.getResults()).thenReturn(List.of(resultInnerMock));
        when(resultInnerMock.getId()).thenReturn(42);
        when(tmdbClientMock._tvSeriesDetails(42, "", "nl")).thenReturn(ResponseEntity.ok(tvSeriesDetailsMock));
        when(tvSeriesDetailsMock.getName()).thenReturn("De Serie");
        when(tvSeriesDetailsMock.getOriginalName()).thenReturn("The Show");
        when(tvSeriesDetailsMock.getOverview()).thenReturn("Een geweldige serie");
        when(tvSeriesDetailsMock.getFirstAirDate()).thenReturn("2024-03-01");
        when(tvSeriesDetailsMock.getId()).thenReturn(42);

        Optional<TMDBResult> result = subject.getMetadata("Show", 2024, "nl");

        assertEquals("De Serie", result.orElseThrow().getTitle());
        org.mockito.Mockito.verify(tmdbClientMock, org.mockito.Mockito.never())._tvSeriesDetails(42, "", "en");
    }
}
