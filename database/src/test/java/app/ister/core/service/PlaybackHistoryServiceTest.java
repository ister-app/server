package app.ister.core.service;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.repository.WatchStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaybackHistoryServiceTest {

    @InjectMocks
    private PlaybackHistoryService subject;

    @Mock
    private WatchStatusService watchStatusService;
    @Mock
    private WatchStatusRepository watchStatusRepository;
    @Mock
    private ContinueWatchingService continueWatchingService;
    @Mock
    private MovieRepository movieRepository;
    @Mock
    private EpisodeRepository episodeRepository;
    @Mock
    private TrackRepository trackRepository;
    @Mock
    private ChapterRepository chapterRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private PodcastEpisodeRepository podcastEpisodeRepository;
    @Mock
    private Authentication authentication;

    private UUID mediaId;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        mediaId = UUID.randomUUID();
        user = UserEntity.builder().id(UUID.randomUUID()).externalId("user-1").build();
        lenient().when(authentication.getName()).thenReturn("user-1");
    }

    private static WatchStatusEntity statusUpdatedAt(Instant updatedAt) {
        WatchStatusEntity status = WatchStatusEntity.builder().build();
        status.setDateUpdated(updatedAt);
        return status;
    }

    // --- history ---

    @Test
    void movieHistoryComesBackNewestFirst() {
        MovieEntity movie = MovieEntity.builder().id(mediaId).build();
        List<WatchStatusEntity> rows = List.of(WatchStatusEntity.builder().movieEntity(movie).build());
        when(movieRepository.findById(mediaId)).thenReturn(Optional.of(movie));
        when(watchStatusRepository.findByUserEntityExternalIdAndMovieEntity(
                eq("user-1"), eq(movie), any(Sort.class))).thenReturn(rows);

        assertEquals(rows, subject.history(authentication, MediaType.MOVIE, mediaId));
    }

    @Test
    void bookHistoryMergesChapterListensSortedNewestFirst() {
        BookEntity book = BookEntity.builder().id(mediaId).build();
        WatchStatusEntity older = statusUpdatedAt(Instant.parse("2026-08-01T10:00:00Z"));
        WatchStatusEntity newer = statusUpdatedAt(Instant.parse("2026-08-20T10:00:00Z"));
        when(bookRepository.findById(mediaId)).thenReturn(Optional.of(book));
        when(watchStatusRepository.findByUserEntityExternalIdAndBookEntity(
                eq("user-1"), eq(book), any(Sort.class))).thenReturn(List.of(older));
        when(watchStatusRepository.findByUserEntityExternalIdAndChapterEntityBookEntity(
                eq("user-1"), eq(book), any(Sort.class))).thenReturn(List.of(newer));

        assertEquals(List.of(newer, older), subject.history(authentication, MediaType.BOOK, mediaId));
    }

    @Test
    void anUnknownMovieThrows() {
        when(movieRepository.findById(mediaId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> subject.history(authentication, MediaType.MOVIE, mediaId));
    }

    // --- markPlayed ---

    @Test
    void markPlayedMovieCreatesAFreshWatchedRow() {
        MovieEntity movie = MovieEntity.builder().id(mediaId).build();
        WatchStatusEntity created = WatchStatusEntity.builder().movieEntity(movie).watched(false).build();
        when(movieRepository.findById(mediaId)).thenReturn(Optional.of(movie));
        when(watchStatusService.getOrCreate(eq(authentication), any(UUID.class), eq(null), eq(movie)))
                .thenReturn(created);

        WatchStatusEntity result = subject.markPlayed(authentication, MediaType.MOVIE, mediaId);

        assertTrue(result.isWatched());
        verify(watchStatusRepository).save(created);
        verify(continueWatchingService).onWatchStatusChanged(created);
    }

    @Test
    void markPlayedTrackUsesAFreshPlayQueueItemId() {
        TrackEntity track = TrackEntity.builder().id(mediaId).build();
        WatchStatusEntity created = WatchStatusEntity.builder().trackEntity(track).build();
        when(trackRepository.findById(mediaId)).thenReturn(Optional.of(track));
        ArgumentCaptor<UUID> queueItemId = ArgumentCaptor.forClass(UUID.class);
        when(watchStatusService.getOrCreateForTrack(eq(authentication), queueItemId.capture(), eq(track)))
                .thenReturn(created);

        subject.markPlayed(authentication, MediaType.TRACK, mediaId);

        // Reusing the media id would collide with itself on a second manual mark.
        assertNotEquals(mediaId, queueItemId.getValue());
    }

    @Test
    void markPlayedBookMarksTheSingleRowFinished() {
        BookEntity book = BookEntity.builder().id(mediaId).build();
        WatchStatusEntity row = WatchStatusEntity.builder().bookEntity(book).watched(false).build();
        when(bookRepository.findById(mediaId)).thenReturn(Optional.of(book));
        when(watchStatusService.getOrCreateForBook(authentication, book)).thenReturn(row);

        WatchStatusEntity result = subject.markPlayed(authentication, MediaType.BOOK, mediaId);

        assertTrue(result.isWatched());
        assertEquals(1.0, result.getReadingProgress());
        verify(watchStatusRepository).save(row);
        verify(continueWatchingService).onWatchStatusChanged(row);
    }

    @Test
    void markPlayedChapterMarksTheSingleRowWatched() {
        ChapterEntity chapter = ChapterEntity.builder().id(mediaId).build();
        WatchStatusEntity row = WatchStatusEntity.builder().chapterEntity(chapter).watched(false).build();
        when(chapterRepository.findById(mediaId)).thenReturn(Optional.of(chapter));
        when(watchStatusService.getOrCreateForChapter(authentication, chapter)).thenReturn(row);

        WatchStatusEntity result = subject.markPlayed(authentication, MediaType.CHAPTER, mediaId);

        assertTrue(result.isWatched());
        verify(continueWatchingService).onWatchStatusChanged(row);
    }

    // --- deleteWatchStatus ---

    @Test
    void deletesAnOwnRowAndRebuildsContinueWatching() {
        UUID id = UUID.randomUUID();
        WatchStatusEntity row = WatchStatusEntity.builder().userEntity(user).build();
        when(watchStatusRepository.findById(id)).thenReturn(Optional.of(row));

        assertTrue(subject.deleteWatchStatus(authentication, id));

        verify(watchStatusRepository).delete(row);
        verify(continueWatchingService).rebuildForUser(user);
    }

    @Test
    void refusesToDeleteAnotherUsersRow() {
        UUID id = UUID.randomUUID();
        UserEntity other = UserEntity.builder().id(UUID.randomUUID()).externalId("someone-else").build();
        WatchStatusEntity row = WatchStatusEntity.builder().userEntity(other).build();
        when(watchStatusRepository.findById(id)).thenReturn(Optional.of(row));

        assertFalse(subject.deleteWatchStatus(authentication, id));

        verify(watchStatusRepository, never()).delete(any(WatchStatusEntity.class));
        verify(continueWatchingService, never()).rebuildForUser(any());
    }

    @Test
    void deletingAMissingRowReturnsFalse() {
        UUID id = UUID.randomUUID();
        when(watchStatusRepository.findById(id)).thenReturn(Optional.empty());

        assertFalse(subject.deleteWatchStatus(authentication, id));

        verify(watchStatusRepository, never()).delete(any(WatchStatusEntity.class));
    }
}
