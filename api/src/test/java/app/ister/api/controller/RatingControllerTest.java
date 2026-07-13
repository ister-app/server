package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BaseEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.RatingEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.RatingMediaType;
import app.ister.core.repository.RatingRepository;
import app.ister.core.service.RatingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RatingControllerTest {

    @InjectMocks
    private RatingController subject;

    @Mock
    private RatingService ratingService;

    @Mock
    private RatingRepository ratingRepository;

    @Mock
    private Authentication authentication;

    @BeforeEach
    void authenticated() {
        lenient().when(authentication.getName()).thenReturn("user-1");
    }

    private static <T> T withId(T entity, UUID id) {
        ((BaseEntity) entity).setId(id);
        return entity;
    }

    @Test
    void setRatingDelegatesToService() {
        UUID mediaId = UUID.randomUUID();

        assertTrue(subject.setRating(RatingMediaType.MOVIE, mediaId, 8, authentication));

        verify(ratingService).setRating(authentication, RatingMediaType.MOVIE, mediaId, 8);
    }

    @Test
    void movieRatingMapsRatedAndUnratedMovies() {
        MovieEntity rated = withId(MovieEntity.builder().name("Heat").releaseYear(1995).build(), UUID.randomUUID());
        MovieEntity unrated = withId(MovieEntity.builder().name("Ronin").releaseYear(1998).build(), UUID.randomUUID());
        when(ratingRepository.findByUserEntityExternalIdAndMovieEntityIn("user-1", List.of(rated, unrated)))
                .thenReturn(List.of(RatingEntity.builder().movieEntity(rated).value(9).build()));

        Map<MovieEntity, Integer> result = subject.movieRating(List.of(rated, unrated), authentication);

        assertEquals(9, result.get(rated));
        assertNull(result.get(unrated));
    }

    @Test
    void showRatingMapsRatedAndUnratedShows() {
        ShowEntity rated = withId(ShowEntity.builder().name("The Wire").build(), UUID.randomUUID());
        ShowEntity unrated = withId(ShowEntity.builder().name("Treme").build(), UUID.randomUUID());
        when(ratingRepository.findByUserEntityExternalIdAndShowEntityIn("user-1", List.of(rated, unrated)))
                .thenReturn(List.of(RatingEntity.builder().showEntity(rated).value(10).build()));

        Map<ShowEntity, Integer> result = subject.showRating(List.of(rated, unrated), authentication);

        assertEquals(10, result.get(rated));
        assertNull(result.get(unrated));
    }

    @Test
    void episodeRatingMapsRatedAndUnratedEpisodes() {
        EpisodeEntity rated = withId(EpisodeEntity.builder().number(1).build(), UUID.randomUUID());
        EpisodeEntity unrated = withId(EpisodeEntity.builder().number(2).build(), UUID.randomUUID());
        when(ratingRepository.findByUserEntityExternalIdAndEpisodeEntityIn("user-1", List.of(rated, unrated)))
                .thenReturn(List.of(RatingEntity.builder().episodeEntity(rated).value(7).build()));

        Map<EpisodeEntity, Integer> result = subject.episodeRating(List.of(rated, unrated), authentication);

        assertEquals(7, result.get(rated));
        assertNull(result.get(unrated));
    }

    @Test
    void albumRatingMapsRatedAndUnratedAlbums() {
        AlbumEntity rated = withId(AlbumEntity.builder().name("Kid A").build(), UUID.randomUUID());
        AlbumEntity unrated = withId(AlbumEntity.builder().name("Amnesiac").build(), UUID.randomUUID());
        when(ratingRepository.findByUserEntityExternalIdAndAlbumEntityIn("user-1", List.of(rated, unrated)))
                .thenReturn(List.of(RatingEntity.builder().albumEntity(rated).value(6).build()));

        Map<AlbumEntity, Integer> result = subject.albumRating(List.of(rated, unrated), authentication);

        assertEquals(6, result.get(rated));
        assertNull(result.get(unrated));
    }

    @Test
    void trackRatingMapsRatedAndUnratedTracks() {
        TrackEntity rated = withId(TrackEntity.builder().number(1).build(), UUID.randomUUID());
        TrackEntity unrated = withId(TrackEntity.builder().number(2).build(), UUID.randomUUID());
        when(ratingRepository.findByUserEntityExternalIdAndTrackEntityIn("user-1", List.of(rated, unrated)))
                .thenReturn(List.of(RatingEntity.builder().trackEntity(rated).value(5).build()));

        Map<TrackEntity, Integer> result = subject.trackRating(List.of(rated, unrated), authentication);

        assertEquals(5, result.get(rated));
        assertNull(result.get(unrated));
    }

    @Test
    void bookRatingMapsRatedAndUnratedBooks() {
        BookEntity rated = withId(BookEntity.builder().name("Dit zijn de namen").build(), UUID.randomUUID());
        BookEntity unrated = withId(BookEntity.builder().name("Joe Speedboot").build(), UUID.randomUUID());
        when(ratingRepository.findByUserEntityExternalIdAndBookEntityIn("user-1", List.of(rated, unrated)))
                .thenReturn(List.of(RatingEntity.builder().bookEntity(rated).value(4).build()));

        Map<BookEntity, Integer> result = subject.bookRating(List.of(rated, unrated), authentication);

        assertEquals(4, result.get(rated));
        assertNull(result.get(unrated));
    }

    @Test
    void podcastRatingMapsRatedAndUnratedPodcasts() {
        PodcastEntity rated = withId(PodcastEntity.builder().title("Serial")
                .feedUrl("https://example.org/a").build(), UUID.randomUUID());
        PodcastEntity unrated = withId(PodcastEntity.builder().title("Radiolab")
                .feedUrl("https://example.org/b").build(), UUID.randomUUID());
        when(ratingRepository.findByUserEntityExternalIdAndPodcastEntityIn("user-1", List.of(rated, unrated)))
                .thenReturn(List.of(RatingEntity.builder().podcastEntity(rated).value(3).build()));

        Map<PodcastEntity, Integer> result = subject.podcastRating(List.of(rated, unrated), authentication);

        assertEquals(3, result.get(rated));
        assertNull(result.get(unrated));
    }
}
