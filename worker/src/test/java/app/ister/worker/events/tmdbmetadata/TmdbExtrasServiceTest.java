package app.ister.worker.events.tmdbmetadata;

import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.tmdbapi.model.MovieKeywords200Response;
import app.ister.tmdbapi.model.MovieKeywords200ResponseKeywordsInner;
import app.ister.tmdbapi.model.MovieReleaseDates200Response;
import app.ister.tmdbapi.model.MovieReleaseDates200ResponseResultsInner;
import app.ister.tmdbapi.model.MovieReleaseDates200ResponseResultsInnerReleaseDatesInner;
import app.ister.tmdbapi.model.MovieVideos200Response;
import app.ister.tmdbapi.model.MovieVideos200ResponseResultsInner;
import app.ister.tmdbapi.model.TvSeriesContentRatings200Response;
import app.ister.tmdbapi.model.TvSeriesContentRatings200ResponseResultsInner;
import app.ister.tmdbapi.model.TvSeriesExternalIds200Response;
import app.ister.tmdbapi.model.TvSeriesKeywords200Response;
import app.ister.tmdbapi.model.TvSeriesKeywords200ResponseResultsInner;
import app.ister.tmdbapi.model.TvSeriesVideos200Response;
import app.ister.worker.clients.TmdbClient;
import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TmdbExtrasServiceTest {

    @Mock
    private TmdbClient tmdbClientMock;

    private TmdbExtrasService subject;
    private MovieEntity movie;
    private ShowEntity show;

    @BeforeEach
    void setUp() {
        subject = new TmdbExtrasService(tmdbClientMock);
        ReflectionTestUtils.setField(subject, "certificationCountry", "NL");
        movie = new MovieEntity();
        show = new ShowEntity();
    }

    private void stubMovieCalls(MovieReleaseDates200Response releaseDates, MovieVideos200Response videos, MovieKeywords200Response keywords) {
        when(tmdbClientMock._movieReleaseDates(1)).thenReturn(ResponseEntity.ok(releaseDates));
        when(tmdbClientMock._movieVideos(1, "en-US")).thenReturn(ResponseEntity.ok(videos));
        when(tmdbClientMock._movieKeywords("1")).thenReturn(ResponseEntity.ok(keywords));
    }

    @Test
    void movieHappyFlow() {
        stubMovieCalls(
                new MovieReleaseDates200Response().results(List.of(
                        new MovieReleaseDates200ResponseResultsInner().iso31661("US").releaseDates(List.of(
                                new MovieReleaseDates200ResponseResultsInnerReleaseDatesInner().certification("PG-13"))),
                        new MovieReleaseDates200ResponseResultsInner().iso31661("NL").releaseDates(List.of(
                                new MovieReleaseDates200ResponseResultsInnerReleaseDatesInner().certification(""),
                                new MovieReleaseDates200ResponseResultsInnerReleaseDatesInner().certification("12"))))),
                new MovieVideos200Response().results(List.of(
                        new MovieVideos200ResponseResultsInner().key("teaser1").site("YouTube").type("Teaser").official(true),
                        new MovieVideos200ResponseResultsInner().key("fan").site("YouTube").type("Trailer").official(false),
                        new MovieVideos200ResponseResultsInner().key("official1").site("YouTube").type("Trailer").official(true),
                        new MovieVideos200ResponseResultsInner().key("vimeo1").site("Vimeo").type("Trailer").official(true))),
                new MovieKeywords200Response().keywords(List.of(
                        new MovieKeywords200ResponseKeywordsInner().name("space"),
                        new MovieKeywords200ResponseKeywordsInner().name("time travel"))));

        subject.fetchForMovie(movie, 1);

        assertEquals("12", movie.getContentRating());
        assertEquals("official1", movie.getTrailerKey());
        assertEquals("YouTube", movie.getTrailerSite());
        assertEquals("space, time travel", movie.getKeywords());
    }

    @Test
    void movieCertificationFallsBackToUs() {
        stubMovieCalls(
                new MovieReleaseDates200Response().results(List.of(
                        new MovieReleaseDates200ResponseResultsInner().iso31661("US").releaseDates(List.of(
                                new MovieReleaseDates200ResponseResultsInnerReleaseDatesInner().certification("R"))),
                        new MovieReleaseDates200ResponseResultsInner().iso31661("DE").releaseDates(List.of(
                                new MovieReleaseDates200ResponseResultsInnerReleaseDatesInner().certification("16"))))),
                new MovieVideos200Response().results(List.of()),
                new MovieKeywords200Response().keywords(List.of()));

        subject.fetchForMovie(movie, 1);

        assertEquals("R", movie.getContentRating());
        assertNull(movie.getTrailerKey());
        assertNull(movie.getKeywords());
    }

    @Test
    void movieCertificationFallsBackToFirstNonEmpty() {
        stubMovieCalls(
                new MovieReleaseDates200Response().results(List.of(
                        new MovieReleaseDates200ResponseResultsInner().iso31661("DE").releaseDates(List.of(
                                new MovieReleaseDates200ResponseResultsInnerReleaseDatesInner().certification("16"))))),
                new MovieVideos200Response().results(List.of()),
                new MovieKeywords200Response().keywords(List.of()));

        subject.fetchForMovie(movie, 1);

        assertEquals("16", movie.getContentRating());
    }

    @Test
    void movieTrailerFallsBackToTeaser() {
        stubMovieCalls(
                new MovieReleaseDates200Response().results(List.of()),
                new MovieVideos200Response().results(List.of(
                        new MovieVideos200ResponseResultsInner().key("clip1").site("YouTube").type("Clip").official(true),
                        new MovieVideos200ResponseResultsInner().key("teaser1").site("YouTube").type("Teaser").official(false))),
                new MovieKeywords200Response().keywords(List.of()));

        subject.fetchForMovie(movie, 1);

        assertEquals("teaser1", movie.getTrailerKey());
    }

    @Test
    void movieFailedCallsLeaveFieldsNull() {
        when(tmdbClientMock._movieReleaseDates(1)).thenThrow(mock(FeignException.class));
        when(tmdbClientMock._movieVideos(1, "en-US")).thenThrow(mock(FeignException.class));
        when(tmdbClientMock._movieKeywords("1")).thenThrow(mock(FeignException.class));

        subject.fetchForMovie(movie, 1);

        assertNull(movie.getContentRating());
        assertNull(movie.getTrailerKey());
        assertNull(movie.getKeywords());
    }

    @Test
    void showHappyFlow() {
        when(tmdbClientMock._tvSeriesContentRatings(2)).thenReturn(ResponseEntity.ok(
                new TvSeriesContentRatings200Response().results(List.of(
                        new TvSeriesContentRatings200ResponseResultsInner().iso31661("US").rating("TV-MA"),
                        new TvSeriesContentRatings200ResponseResultsInner().iso31661("NL").rating("16")))));
        when(tmdbClientMock._tvSeriesExternalIds(2)).thenReturn(ResponseEntity.ok(
                new TvSeriesExternalIds200Response().imdbId("tt0944947")));
        when(tmdbClientMock._tvSeriesVideos(2, null, "en-US")).thenReturn(ResponseEntity.ok(
                new TvSeriesVideos200Response().results(List.of(
                        new app.ister.tmdbapi.model.TvSeriesVideos200ResponseResultsInner().key("show1").site("YouTube").type("Trailer").official(true)))));
        when(tmdbClientMock._tvSeriesKeywords(2)).thenReturn(ResponseEntity.ok(
                new TvSeriesKeywords200Response().results(List.of(
                        new TvSeriesKeywords200ResponseResultsInner().name("dragons")))));

        subject.fetchForShow(show, 2);

        assertEquals("16", show.getContentRating());
        assertEquals("tt0944947", show.getImdbId());
        assertEquals("show1", show.getTrailerKey());
        assertEquals("YouTube", show.getTrailerSite());
        assertEquals("dragons", show.getKeywords());
    }

    @Test
    void showFailedCallsLeaveFieldsNull() {
        when(tmdbClientMock._tvSeriesContentRatings(2)).thenThrow(mock(FeignException.class));
        when(tmdbClientMock._tvSeriesExternalIds(2)).thenThrow(mock(FeignException.class));
        when(tmdbClientMock._tvSeriesVideos(2, null, "en-US")).thenThrow(mock(FeignException.class));
        when(tmdbClientMock._tvSeriesKeywords(2)).thenThrow(mock(FeignException.class));

        subject.fetchForShow(show, 2);

        assertNull(show.getContentRating());
        assertNull(show.getImdbId());
        assertNull(show.getTrailerKey());
        assertNull(show.getKeywords());
    }
}
