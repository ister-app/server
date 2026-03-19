package app.ister.worker.events.tmdbmetadata;

import app.ister.tmdbapi.model.SearchTv200Response;
import app.ister.tmdbapi.model.SearchTv200ResponseResultsInner;
import app.ister.tmdbapi.model.TvSeriesDetails200Response;
import app.ister.worker.clients.TmdbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

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
        subject = new ShowMetadata(tmdbClientMock);
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
}
