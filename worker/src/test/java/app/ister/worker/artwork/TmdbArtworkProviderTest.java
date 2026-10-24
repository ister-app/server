package app.ister.worker.artwork;

import app.ister.core.artwork.ImageTarget;
import app.ister.core.artwork.RemoteImageCandidate;
import app.ister.core.config.LanguageProperties;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.MetadataSource;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.tmdbapi.model.MovieImages200Response;
import app.ister.tmdbapi.model.MovieImages200ResponseBackdropsInner;
import app.ister.tmdbapi.model.MovieImages200ResponsePostersInner;
import app.ister.tmdbapi.model.TvEpisodeImages200Response;
import app.ister.tmdbapi.model.TvEpisodeImages200ResponseStillsInner;
import app.ister.worker.clients.TmdbClient;
import app.ister.worker.events.tmdbmetadata.TmdbImageBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TmdbArtworkProviderTest {

    private final TmdbClient tmdb = mock(TmdbClient.class);
    private final MovieRepository movies = mock(MovieRepository.class);
    private final ShowRepository shows = mock(ShowRepository.class);
    private final EpisodeRepository episodes = mock(EpisodeRepository.class);
    private final PersonRepository persons = mock(PersonRepository.class);
    private TmdbArtworkProvider subject;

    @BeforeEach
    void setUp() {
        LanguageProperties languages = new LanguageProperties();
        languages.setLanguages(List.of("en", "nl"));
        subject = new TmdbArtworkProvider(tmdb, new TmdbImageBase("https://image.tmdb.org/t/p/original"),
                languages, movies, shows, episodes, persons);
    }

    @Test
    void moviePostersBecomeCoverCandidatesWithTheScanSourceUri() {
        UUID id = UUID.randomUUID();
        MovieEntity movie = MovieEntity.builder().id(id).tmdbId(603).build();
        when(movies.findById(id)).thenReturn(Optional.of(movie));
        MovieImages200Response body = new MovieImages200Response();
        MovieImages200ResponsePostersInner poster = new MovieImages200ResponsePostersInner();
        poster.setFilePath("/p1.jpg");
        poster.setWidth(2000);
        poster.setHeight(3000);
        poster.setIso6391("nl");
        poster.setVoteAverage(new BigDecimal("5.4"));
        body.setPosters(List.of(poster));
        MovieImages200ResponseBackdropsInner backdrop = new MovieImages200ResponseBackdropsInner();
        backdrop.setFilePath("/b1.jpg");
        body.setBackdrops(List.of(backdrop));
        when(tmdb._movieImages(eq(603), eq("en,nl,null"), isNull())).thenReturn(ResponseEntity.ok(body));

        List<RemoteImageCandidate> covers = subject.candidates(new ImageTarget(ImageTarget.Kind.MOVIE, id), ImageType.COVER);
        List<RemoteImageCandidate> backgrounds = subject.candidates(new ImageTarget(ImageTarget.Kind.MOVIE, id), ImageType.BACKGROUND);

        assertThat(covers).hasSize(1);
        RemoteImageCandidate c = covers.getFirst();
        assertThat(c.sourceUri()).isEqualTo("TMDB://https://image.tmdb.org/t/p/original/p1.jpg");
        assertThat(c.previewUrl()).isEqualTo("https://image.tmdb.org/t/p/w500/p1.jpg");
        assertThat(c.source()).isEqualTo(MetadataSource.TMDB);
        assertThat(c.language()).isEqualTo("nld");
        assertThat(c.width()).isEqualTo(2000);
        assertThat(c.score()).isEqualTo(5.4);
        assertThat(backgrounds).extracting(RemoteImageCandidate::sourceUri)
                .containsExactly("TMDB://https://image.tmdb.org/t/p/original/b1.jpg");
    }

    @Test
    void episodeStillsAreLookedUpThroughTheShowAndSeasonNumbers() {
        UUID id = UUID.randomUUID();
        ShowEntity show = ShowEntity.builder().tmdbId(1399).build();
        SeasonEntity season = SeasonEntity.builder().number(2).build();
        EpisodeEntity episode = EpisodeEntity.builder().id(id).showEntity(show).seasonEntity(season).number(7).build();
        when(episodes.findById(id)).thenReturn(Optional.of(episode));
        TvEpisodeImages200Response body = new TvEpisodeImages200Response();
        TvEpisodeImages200ResponseStillsInner still = new TvEpisodeImages200ResponseStillsInner();
        still.setFilePath("/s.jpg");
        body.setStills(List.of(still));
        when(tmdb._tvEpisodeImages(eq(1399), eq(2), eq(7), any(), isNull())).thenReturn(ResponseEntity.ok(body));

        List<RemoteImageCandidate> result = subject.candidates(new ImageTarget(ImageTarget.Kind.EPISODE, id), ImageType.BACKGROUND);

        assertThat(result).extracting(RemoteImageCandidate::language).containsExactly((String) null);
        assertThat(result.getFirst().sourceUri()).endsWith("/s.jpg");
    }

    @Test
    void itemsWithoutATmdbIdOrOfOtherKindsHaveNoCandidates() {
        UUID id = UUID.randomUUID();
        when(movies.findById(id)).thenReturn(Optional.of(MovieEntity.builder().id(id).build()));
        assertThat(subject.candidates(new ImageTarget(ImageTarget.Kind.MOVIE, id), ImageType.COVER)).isEmpty();
        assertThat(subject.candidates(new ImageTarget(ImageTarget.Kind.ALBUM, id), ImageType.COVER)).isEmpty();
    }

    @Test
    void aTmdbFailureYieldsNoCandidatesInsteadOfAnError() {
        UUID id = UUID.randomUUID();
        when(movies.findById(id)).thenReturn(Optional.of(MovieEntity.builder().id(id).tmdbId(1).build()));
        when(tmdb._movieImages(any(), any(), any())).thenThrow(new RuntimeException("503"));
        assertThat(subject.candidates(new ImageTarget(ImageTarget.Kind.MOVIE, id), ImageType.COVER)).isEmpty();
    }

    @Test
    void downloadUrlOnlyForTmdbImageUris() {
        assertThat(subject.downloadUrl("TMDB://https://image.tmdb.org/t/p/original/p1.jpg"))
                .contains("https://image.tmdb.org/t/p/original/p1.jpg");
        assertThat(subject.downloadUrl("tmdb://https://image.tmdb.org/t/p/original/p1.jpg")).isPresent();
        assertThat(subject.downloadUrl("TMDB://https://evil.example/p1.jpg")).isEmpty();
        assertThat(subject.downloadUrl("file:///x.jpg")).isEmpty();
        assertThat(subject.downloadUrl(null)).isEmpty();
    }

    @Test
    void iso3ConversionHandlesNullsAndUnknowns() {
        assertThat(TmdbArtworkProvider.iso3Of(null)).isNull();
        assertThat(TmdbArtworkProvider.iso3Of("null")).isNull();
        assertThat(TmdbArtworkProvider.iso3Of("en")).isEqualTo("eng");
        assertThat(TmdbArtworkProvider.iso3Of("xx")).isNull();
    }
}
