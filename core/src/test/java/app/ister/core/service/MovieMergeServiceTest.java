package app.ister.core.service;

import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.RatingEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.repository.ContinueWatchingRepository;
import app.ister.core.repository.CreditRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PlayQueueItemRepository;
import app.ister.core.repository.PlaylistItemRepository;
import app.ister.core.repository.RatingRepository;
import app.ister.core.repository.WatchStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MovieMergeServiceTest {
    @InjectMocks
    private MovieMergeService subject;
    @Mock
    private MovieRepository movieRepository;
    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private PlayQueueItemRepository playQueueItemRepository;
    @Mock
    private PlaylistItemRepository playlistItemRepository;
    @Mock
    private WatchStatusRepository watchStatusRepository;
    @Mock
    private RatingRepository ratingRepository;
    @Mock
    private ContinueWatchingRepository continueWatchingRepository;
    @Mock
    private CreditRepository creditRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private MetadataRepository metadataRepository;
    @Mock
    private ContinueWatchingService continueWatchingService;
    @Mock
    private ServerEventService serverEventService;

    private final MovieEntity source = MovieEntity.builder().id(UUID.randomUUID()).name("De Smurfen").releaseYear(2011).build();
    private final MovieEntity target = MovieEntity.builder().id(UUID.randomUUID()).name("The Smurfs").releaseYear(2011).tmdbId(41513).build();
    private final UserEntity user = UserEntity.builder().id(UUID.randomUUID()).build();

    @BeforeEach
    void setUp() {
        when(mediaFileRepository.moveToMovie(source, target)).thenReturn(1);
    }

    @Test
    void movesFilesQueuesAndPlaylistsThenDropsTheSourceRow() {
        subject.mergeInto(source, target);

        InOrder order = inOrder(mediaFileRepository, playQueueItemRepository, playlistItemRepository,
                continueWatchingRepository, creditRepository, imageRepository, metadataRepository, movieRepository, serverEventService);
        order.verify(mediaFileRepository).moveToMovie(source, target);
        order.verify(playQueueItemRepository).moveMovie(source.getId(), target.getId());
        order.verify(playlistItemRepository).moveMovie(source.getId(), target.getId());
        order.verify(continueWatchingRepository).deleteByMovieId(source.getId());
        order.verify(creditRepository).deleteByMovieEntityId(source.getId());
        order.verify(imageRepository).deleteByMovieId(source.getId());
        order.verify(metadataRepository).deleteByMovieId(source.getId());
        order.verify(movieRepository).deleteRowById(source.getId());
        order.verify(serverEventService).createSearchDeleteEvent(SearchEntityType.MOVIE, source.getId());
        order.verify(serverEventService).createSearchIndexEvent(SearchEntityType.MOVIE, target.getId());
    }

    @Test
    void movesWatchStatusAndRecomputesContinueWatching() {
        WatchStatusEntity status = WatchStatusEntity.builder().userEntity(user).movieEntity(source).build();
        when(watchStatusRepository.findByMovieEntity(source)).thenReturn(List.of(status));

        subject.mergeInto(source, target);

        assertEquals(target, status.getMovieEntity());
        verify(watchStatusRepository).save(status);
        verify(continueWatchingService).onWatchStatusChanged(status);
    }

    @Test
    void movesARatingUnlessTheUserAlreadyRatedTheTarget() {
        RatingEntity moved = RatingEntity.builder().userEntity(user).movieEntity(source).build();
        UserEntity other = UserEntity.builder().id(UUID.randomUUID()).build();
        RatingEntity dropped = RatingEntity.builder().userEntity(other).movieEntity(source).build();
        when(ratingRepository.findByMovieEntity(source)).thenReturn(List.of(moved, dropped));
        when(ratingRepository.findByUserEntityAndMovieEntity(user, target)).thenReturn(Optional.empty());
        when(ratingRepository.findByUserEntityAndMovieEntity(other, target))
                .thenReturn(Optional.of(RatingEntity.builder().userEntity(other).movieEntity(target).build()));

        subject.mergeInto(source, target);

        assertEquals(target, moved.getMovieEntity());
        verify(ratingRepository).save(moved);
        verify(ratingRepository).delete(dropped);
        verify(ratingRepository, never()).save(dropped);
    }
}
