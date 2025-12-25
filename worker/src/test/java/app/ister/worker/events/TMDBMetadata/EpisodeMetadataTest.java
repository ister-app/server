package app.ister.worker.events.TMDBMetadata;

import app.ister.tmdbapi.model.SearchTv200Response;
import app.ister.tmdbapi.model.SearchTv200ResponseResultsInner;
import app.ister.tmdbapi.model.TvEpisodeDetails200Response;
import app.ister.worker.clients.TmdbClient;
import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpisodeMetadataTest {
    @Mock
    private TmdbClient tmdbClientMock;
    @Mock
    private SearchTv200Response searchTv200ResponseMock;
    @Mock
    private SearchTv200ResponseResultsInner searchTv200ResponseResultsInnerMock;
    @Mock
    private TvEpisodeDetails200Response tvEpisodeDetails200ResponseMock;
    private EpisodeMetadata subject;

    @BeforeEach
    void setUp() {
        subject = new EpisodeMetadata(tmdbClientMock);
    }

    @Test
    void happyFlow() throws FeignException {
        when(tmdbClientMock._searchTv("Showname", null, null, null, null, 2024)).thenReturn(ResponseEntity.ok(searchTv200ResponseMock));
        when(searchTv200ResponseMock.getResults()).thenReturn(List.of(searchTv200ResponseResultsInnerMock));
        when(searchTv200ResponseResultsInnerMock.getId()).thenReturn(1);
        when(tmdbClientMock._tvEpisodeDetails(1, 1, 1, "", "en")).thenReturn(ResponseEntity.ok(tvEpisodeDetails200ResponseMock));
        when(tvEpisodeDetails200ResponseMock.getAirDate()).thenReturn("2024-04-01");
        when(tvEpisodeDetails200ResponseMock.getOverview()).thenReturn("overview");
        when(tvEpisodeDetails200ResponseMock.getEpisodeNumber()).thenReturn(1);
        when(tvEpisodeDetails200ResponseMock.getName()).thenReturn("name");
        when(tvEpisodeDetails200ResponseMock.getId()).thenReturn(1);
        when(tvEpisodeDetails200ResponseMock.getStillPath()).thenReturn("/still-path");

        TMDBResult expected = TMDBResult.builder()
                .language("eng")
                .title("name")
                .released(LocalDate.parse("2024-04-01"))
                .sourceUri("")
                .description("overview")
                .sourceUri("TMDB://1")
                .backgroundUrl("https://image.tmdb.org/t/p/original/still-path").build();

        Optional<TMDBResult> result = subject.getMetadata("Showname", 2024, 1, 1, "en");
        assertThat(result.get()).usingRecursiveComparison().isEqualTo(expected);
    }

    @Test
    void returnsNullWhenNoResults() throws FeignException {
        when(tmdbClientMock._searchTv("Showname", null, null, null, null, 2024)).thenReturn(ResponseEntity.ok(searchTv200ResponseMock));
        when(searchTv200ResponseMock.getResults()).thenReturn(List.of());
        Optional<TMDBResult> result = subject.getMetadata("Showname", 2024, 1, 1, "en");
        assertTrue(result.isEmpty());
    }
}
