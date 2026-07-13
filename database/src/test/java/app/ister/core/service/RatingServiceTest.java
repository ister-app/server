package app.ister.core.service;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.RatingEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.RatingMediaType;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.repository.RatingRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RatingServiceTest {

    @InjectMocks
    private RatingService subject;

    @Mock
    private UserService userService;
    @Mock
    private RatingRepository ratingRepository;
    @Mock
    private MovieRepository movieRepository;
    @Mock
    private ShowRepository showRepository;
    @Mock
    private EpisodeRepository episodeRepository;
    @Mock
    private AlbumRepository albumRepository;
    @Mock
    private TrackRepository trackRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private PodcastRepository podcastRepository;
    @Mock
    private Authentication authentication;

    private UserEntity user;
    private UUID mediaId;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(UUID.randomUUID()).build();
        mediaId = UUID.randomUUID();
        lenient().when(userService.getOrCreateUser(authentication)).thenReturn(user);
    }

    private RatingEntity captureSavedRating() {
        ArgumentCaptor<RatingEntity> captor = ArgumentCaptor.forClass(RatingEntity.class);
        verify(ratingRepository).save(captor.capture());
        return captor.getValue();
    }

    // --- validation ---

    @Test
    void ratingBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> subject.setRating(authentication, RatingMediaType.MOVIE, mediaId, 0));
    }

    @Test
    void ratingAboveTenIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> subject.setRating(authentication, RatingMediaType.MOVIE, mediaId, 11));
    }

    // --- create / update / clear ---

    @Test
    void createsANewMovieRating() {
        MovieEntity movie = MovieEntity.builder().id(mediaId).build();
        when(movieRepository.findById(mediaId)).thenReturn(Optional.of(movie));
        when(ratingRepository.findByUserEntityAndMovieEntity(user, movie)).thenReturn(Optional.empty());

        subject.setRating(authentication, RatingMediaType.MOVIE, mediaId, 8);

        RatingEntity saved = captureSavedRating();
        assertEquals(8, saved.getValue());
        assertEquals(movie, saved.getMovieEntity());
        assertEquals(user, saved.getUserEntity());
    }

    @Test
    void updatesAnExistingRatingInPlace() {
        MovieEntity movie = MovieEntity.builder().id(mediaId).build();
        RatingEntity existing = RatingEntity.builder().userEntity(user).movieEntity(movie).value(3).build();
        when(movieRepository.findById(mediaId)).thenReturn(Optional.of(movie));
        when(ratingRepository.findByUserEntityAndMovieEntity(user, movie)).thenReturn(Optional.of(existing));

        subject.setRating(authentication, RatingMediaType.MOVIE, mediaId, 9);

        assertEquals(9, existing.getValue());
        verify(ratingRepository).save(existing);
    }

    @Test
    void aNullRatingDeletesTheExistingRating() {
        MovieEntity movie = MovieEntity.builder().id(mediaId).build();
        RatingEntity existing = RatingEntity.builder().userEntity(user).movieEntity(movie).value(3).build();
        when(movieRepository.findById(mediaId)).thenReturn(Optional.of(movie));
        when(ratingRepository.findByUserEntityAndMovieEntity(user, movie)).thenReturn(Optional.of(existing));

        subject.setRating(authentication, RatingMediaType.MOVIE, mediaId, null);

        verify(ratingRepository).delete(existing);
        verify(ratingRepository, never()).save(any());
    }

    @Test
    void aNullRatingWithoutAnExistingRatingIsANoOp() {
        MovieEntity movie = MovieEntity.builder().id(mediaId).build();
        when(movieRepository.findById(mediaId)).thenReturn(Optional.of(movie));
        when(ratingRepository.findByUserEntityAndMovieEntity(user, movie)).thenReturn(Optional.empty());

        subject.setRating(authentication, RatingMediaType.MOVIE, mediaId, null);

        verify(ratingRepository, never()).delete(any());
        verify(ratingRepository, never()).save(any());
    }

    // --- every media type ---

    @Test
    void ratesAShow() {
        ShowEntity show = ShowEntity.builder().id(mediaId).build();
        when(showRepository.findById(mediaId)).thenReturn(Optional.of(show));
        when(ratingRepository.findByUserEntityAndShowEntity(user, show)).thenReturn(Optional.empty());

        subject.setRating(authentication, RatingMediaType.SHOW, mediaId, 7);

        assertEquals(show, captureSavedRating().getShowEntity());
    }

    @Test
    void ratesAnEpisode() {
        EpisodeEntity episode = EpisodeEntity.builder().id(mediaId).build();
        when(episodeRepository.findById(mediaId)).thenReturn(Optional.of(episode));
        when(ratingRepository.findByUserEntityAndEpisodeEntity(user, episode)).thenReturn(Optional.empty());

        subject.setRating(authentication, RatingMediaType.EPISODE, mediaId, 7);

        assertEquals(episode, captureSavedRating().getEpisodeEntity());
    }

    @Test
    void ratesAnAlbum() {
        AlbumEntity album = AlbumEntity.builder().id(mediaId).build();
        when(albumRepository.findById(mediaId)).thenReturn(Optional.of(album));
        when(ratingRepository.findByUserEntityAndAlbumEntity(user, album)).thenReturn(Optional.empty());

        subject.setRating(authentication, RatingMediaType.ALBUM, mediaId, 7);

        assertEquals(album, captureSavedRating().getAlbumEntity());
    }

    @Test
    void ratesATrack() {
        TrackEntity track = TrackEntity.builder().id(mediaId).build();
        when(trackRepository.findById(mediaId)).thenReturn(Optional.of(track));
        when(ratingRepository.findByUserEntityAndTrackEntity(user, track)).thenReturn(Optional.empty());

        subject.setRating(authentication, RatingMediaType.TRACK, mediaId, 7);

        assertEquals(track, captureSavedRating().getTrackEntity());
    }

    @Test
    void ratesABook() {
        BookEntity book = BookEntity.builder().id(mediaId).build();
        when(bookRepository.findById(mediaId)).thenReturn(Optional.of(book));
        when(ratingRepository.findByUserEntityAndBookEntity(user, book)).thenReturn(Optional.empty());

        subject.setRating(authentication, RatingMediaType.BOOK, mediaId, 10);

        assertEquals(book, captureSavedRating().getBookEntity());
    }

    @Test
    void ratesAPodcast() {
        PodcastEntity podcast = PodcastEntity.builder().id(mediaId).build();
        when(podcastRepository.findById(mediaId)).thenReturn(Optional.of(podcast));
        when(ratingRepository.findByUserEntityAndPodcastEntity(user, podcast)).thenReturn(Optional.empty());

        subject.setRating(authentication, RatingMediaType.PODCAST, mediaId, 1);

        assertEquals(podcast, captureSavedRating().getPodcastEntity());
    }

    // --- missing media ---

    @Test
    void anUnknownMovieThrows() {
        when(movieRepository.findById(mediaId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> subject.setRating(authentication, RatingMediaType.MOVIE, mediaId, 5));
    }

    @Test
    void anUnknownTrackThrows() {
        when(trackRepository.findById(mediaId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> subject.setRating(authentication, RatingMediaType.TRACK, mediaId, 5));
    }
}
