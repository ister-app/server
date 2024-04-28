package app.ister.server.eventHandlers.TMDBMetadata;

import info.movito.themoviedbapi.TmdbApi;
import info.movito.themoviedbapi.TmdbSearch;
import info.movito.themoviedbapi.TmdbTvEpisodes;
import info.movito.themoviedbapi.model.core.TvSeries;
import info.movito.themoviedbapi.model.core.TvSeriesResultsPage;
import info.movito.themoviedbapi.model.tv.episode.TvEpisodeDb;
import info.movito.themoviedbapi.tools.TmdbException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpisodeMetadataTest {
    @Mock
    private TmdbApi tmdbApiMock;
    @Mock
    private TmdbSearch tmdbSearchMock;
    @Mock
    private TvSeriesResultsPage tvSeriesResultsPageMock;
    @Mock
    private TmdbTvEpisodes tmdbTvEpisodesMock;


    private EpisodeMetadata subject;

    TvSeries tvSeries = new TvSeries();
    TvEpisodeDb tvEpisode = new TvEpisodeDb();

    @BeforeEach
    void setUp() {
        subject = new EpisodeMetadata(tmdbApiMock);
        tvSeries.setId(1);
        tvEpisode.setEpisodeNumber(1);
        tvEpisode.setName("name");
        tvEpisode.setAirDate("2024-04-01");
        tvEpisode.setId(1);
        tvEpisode.setOverview("overview");
        tvEpisode.setStillPath("/still-path");
    }

    @Test
    void happyFlow() throws TmdbException {
        when(tmdbApiMock.getSearch()).thenReturn(tmdbSearchMock);
        when(tmdbSearchMock.searchTv("Showname", null, null, null,null, 2024)).thenReturn(tvSeriesResultsPageMock);
        when(tvSeriesResultsPageMock.getResults()).thenReturn(List.of(tvSeries));

        when(tmdbApiMock.getTvEpisodes()).thenReturn(tmdbTvEpisodesMock);
        when(tmdbTvEpisodesMock.getDetails(1, 1, 1, "en")).thenReturn(tvEpisode);

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
    void returnsNullWhenNoResults() throws TmdbException {
        when(tmdbApiMock.getSearch()).thenReturn(tmdbSearchMock);
        when(tmdbSearchMock.searchTv("Showname", null, null, null,null, 2024)).thenReturn(tvSeriesResultsPageMock);
        when(tvSeriesResultsPageMock.getResults()).thenReturn(List.of());
        Optional<TMDBResult> result = subject.getMetadata("Showname", 2024, 1, 1, "en");
        assertTrue(result.isEmpty());
    }
}
