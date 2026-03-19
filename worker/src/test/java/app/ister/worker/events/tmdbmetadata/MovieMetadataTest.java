package app.ister.worker.events.tmdbmetadata;

import app.ister.tmdbapi.model.MovieDetails200Response;
import app.ister.tmdbapi.model.SearchMovie200Response;
import app.ister.tmdbapi.model.SearchMovie200ResponseResultsInner;
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
        subject = new MovieMetadata(tmdbClientMock);
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
}
