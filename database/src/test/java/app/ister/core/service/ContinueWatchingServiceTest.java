package app.ister.core.service;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.ContinueWatchingEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.ContinueWatchingRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.WatchStatusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContinueWatchingServiceTest {

    @InjectMocks
    private ContinueWatchingService subject;

    @Mock private ContinueWatchingRepository continueWatchingRepository;
    @Mock private WatchStatusRepository watchStatusRepository;
    @Mock private EpisodeRepository episodeRepository;
    @Mock private ChapterRepository chapterRepository;

    private final UserEntity user = user();
    private final ShowEntity show = show();

    // ===== Episodes =====

    @Test
    void anUnfinishedEpisodeResumesItself() {
        EpisodeEntity episode = episode(2, 5);

        subject.onWatchStatusChanged(episodeStatus(episode, false));

        assertEquals(episode.getId(), upsertedEpisodeId());
        verify(episodeRepository, never()).findNextUnwatchedEpisodeId(any(), any(), anyInt(), anyInt());
    }

    @Test
    void afinishedEpisodeHandsOverToTheNextUnwatchedOne() {
        EpisodeEntity episode = episode(2, 5);
        UUID nextId = UUID.randomUUID();
        when(episodeRepository.findNextUnwatchedEpisodeId(show.getId(), user.getId(), 2, 5))
                .thenReturn(List.of(nextId));

        subject.onWatchStatusChanged(episodeStatus(episode, true));

        assertEquals(nextId, upsertedEpisodeId());
    }

    /** The user is up to date with the show: the entry survives, with nothing to resume. */
    @Test
    void aFinishedShowKeepsItsEntryWithoutATarget() {
        EpisodeEntity episode = episode(2, 5);
        when(episodeRepository.findNextUnwatchedEpisodeId(show.getId(), user.getId(), 2, 5))
                .thenReturn(List.of());

        subject.onWatchStatusChanged(episodeStatus(episode, true));

        assertNull(upsertedEpisodeId());
        verify(continueWatchingRepository).upsert(eq(user.getId()), eq(MediaType.EPISODE.name()), eq(show.getId()),
                any(), any(), any(), any(), any(), any());
    }

    /** A newly scanned episode is what the user should continue that finished show with. */
    @Test
    void aNewEpisodeRevivesTheShowsEntry() {
        ContinueWatchingEntity exhausted = ContinueWatchingEntity.builder()
                .userEntity(user)
                .entryType(MediaType.EPISODE)
                .groupId(show.getId())
                .lastWatched(Instant.now())
                .build();
        EpisodeEntity newEpisode = episode(3, 1);
        when(continueWatchingRepository.findExhaustedShowEntries(show.getId())).thenReturn(List.of(exhausted));
        when(episodeRepository.findNextUnwatchedEpisodeId(eq(show.getId()), eq(user.getId()), anyInt(), anyInt()))
                .thenReturn(List.of(newEpisode.getId()));
        when(episodeRepository.getReferenceById(newEpisode.getId())).thenReturn(newEpisode);

        subject.recomputeForShow(show.getId());

        assertEquals(newEpisode, exhausted.getEpisodeEntity());
        verify(continueWatchingRepository).save(exhausted);
    }

    @Test
    void aShowWithoutNewEpisodesIsLeftAlone() {
        ContinueWatchingEntity exhausted = ContinueWatchingEntity.builder()
                .userEntity(user)
                .entryType(MediaType.EPISODE)
                .groupId(show.getId())
                .lastWatched(Instant.now())
                .build();
        when(continueWatchingRepository.findExhaustedShowEntries(show.getId())).thenReturn(List.of(exhausted));
        when(episodeRepository.findNextUnwatchedEpisodeId(eq(show.getId()), eq(user.getId()), anyInt(), anyInt()))
                .thenReturn(List.of());

        subject.recomputeForShow(show.getId());

        assertNull(exhausted.getEpisodeEntity());
        verify(continueWatchingRepository, never()).save(any());
    }

    // ===== Chapters =====

    @Test
    void aFinishedChapterHandsOverToTheNextUnfinishedOne() {
        BookEntity book = book();
        ChapterEntity chapter = chapter(book, 4);
        UUID nextId = UUID.randomUUID();
        when(chapterRepository.findNextUnfinishedChapterId(book.getId(), user.getId(), 4))
                .thenReturn(List.of(nextId));

        WatchStatusEntity status = WatchStatusEntity.builder()
                .userEntity(user).playQueueItemId(chapter.getId()).chapterEntity(chapter).watched(true).build();
        subject.onWatchStatusChanged(status);

        verify(continueWatchingRepository).upsert(eq(user.getId()), eq(MediaType.CHAPTER.name()), eq(book.getId()),
                isNull(), isNull(), eq(nextId), isNull(), isNull(), any());
    }

    // ===== Movies, books, podcasts: the item itself, until it is done =====

    @Test
    void aFinishedMovieLeavesNothingToResume() {
        MovieEntity movie = movie();
        WatchStatusEntity status = WatchStatusEntity.builder()
                .userEntity(user).playQueueItemId(UUID.randomUUID()).movieEntity(movie).watched(true).build();

        subject.onWatchStatusChanged(status);

        verify(continueWatchingRepository).upsert(eq(user.getId()), eq(MediaType.MOVIE.name()), eq(movie.getId()),
                isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    void anUnfinishedMovieResumesItself() {
        MovieEntity movie = movie();
        WatchStatusEntity status = WatchStatusEntity.builder()
                .userEntity(user).playQueueItemId(UUID.randomUUID()).movieEntity(movie).watched(false).build();

        subject.onWatchStatusChanged(status);

        verify(continueWatchingRepository).upsert(eq(user.getId()), eq(MediaType.MOVIE.name()), eq(movie.getId()),
                isNull(), eq(movie.getId()), isNull(), isNull(), isNull(), any());
    }

    /** An epub the user opened but never got anywhere in is not something to continue. */
    @Test
    void aBookWithoutReadingProgressIsNotSomethingToContinue() {
        BookEntity book = book();
        WatchStatusEntity status = WatchStatusEntity.builder()
                .userEntity(user).playQueueItemId(book.getId()).bookEntity(book).watched(false)
                .readingProgress(0.0).build();

        subject.onWatchStatusChanged(status);

        verify(continueWatchingRepository).upsert(eq(user.getId()), eq(MediaType.BOOK.name()), eq(book.getId()),
                isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    void aPodcastEpisodeInProgressResumesItself() {
        PodcastEpisodeEntity episode = PodcastEpisodeEntity.builder().guid("guid-1").build();
        episode.setId(UUID.randomUUID());
        WatchStatusEntity status = WatchStatusEntity.builder()
                .userEntity(user).playQueueItemId(UUID.randomUUID()).podcastEpisodeEntity(episode)
                .watched(false).progressInMilliseconds(120_000).build();

        subject.onWatchStatusChanged(status);

        verify(continueWatchingRepository).upsert(eq(user.getId()), eq(MediaType.PODCAST_EPISODE.name()),
                eq(episode.getId()), isNull(), isNull(), isNull(), isNull(), eq(episode.getId()), any());
    }

    // ===== Rebuild =====

    @Test
    void rebuildThrowsAwayTheOldEntriesAndRecomputesFromTheWatchHistory() {
        ReflectionTestUtils.setField(subject, "historyDays", 150);
        EpisodeEntity episode = episode(1, 2);
        Instant lastWatched = Instant.now().minusSeconds(60);
        when(watchStatusRepository.findRecentEpisodeEntries(eq(user.getId()), any()))
                .thenReturn(List.of(recentEntry(episode.getId(), show.getId(), lastWatched, false)));
        when(watchStatusRepository.findRecentChapterEntries(eq(user.getId()), any())).thenReturn(List.of());
        when(watchStatusRepository.findRecentMovieEntries(eq(user.getId()), any())).thenReturn(List.of());
        when(watchStatusRepository.findRecentBookEntries(eq(user.getId()), any())).thenReturn(List.of());
        when(watchStatusRepository.findRecentPodcastEpisodeEntries(eq(user.getId()), any())).thenReturn(List.of());

        subject.rebuildForUser(user);

        verify(continueWatchingRepository).deleteByUserEntityId(user.getId());
        verify(continueWatchingRepository).upsert(eq(user.getId()), eq(MediaType.EPISODE.name()), eq(show.getId()),
                eq(episode.getId()), isNull(), isNull(), isNull(), isNull(), eq(lastWatched));
    }

    @Test
    void rebuildHandsAFinishedEpisodeOverToTheNextOne() {
        ReflectionTestUtils.setField(subject, "historyDays", 150);
        EpisodeEntity episode = episode(1, 2);
        UUID nextId = UUID.randomUUID();
        Instant lastWatched = Instant.now().minusSeconds(60);
        when(watchStatusRepository.findRecentEpisodeEntries(eq(user.getId()), any()))
                .thenReturn(List.of(recentEntry(episode.getId(), show.getId(), lastWatched, true)));
        when(watchStatusRepository.findRecentChapterEntries(eq(user.getId()), any())).thenReturn(List.of());
        when(watchStatusRepository.findRecentMovieEntries(eq(user.getId()), any())).thenReturn(List.of());
        when(watchStatusRepository.findRecentBookEntries(eq(user.getId()), any())).thenReturn(List.of());
        when(watchStatusRepository.findRecentPodcastEpisodeEntries(eq(user.getId()), any())).thenReturn(List.of());
        when(episodeRepository.findById(episode.getId())).thenReturn(Optional.of(episode));
        when(episodeRepository.findNextUnwatchedEpisodeId(show.getId(), user.getId(), 1, 2))
                .thenReturn(List.of(nextId));

        subject.rebuildForUser(user);

        verify(continueWatchingRepository).upsert(eq(user.getId()), eq(MediaType.EPISODE.name()), eq(show.getId()),
                eq(nextId), isNull(), isNull(), isNull(), isNull(), eq(lastWatched));
    }

    // ===== Helpers =====

    private UUID upsertedEpisodeId() {
        ArgumentCaptor<UUID> episodeId = ArgumentCaptor.forClass(UUID.class);
        verify(continueWatchingRepository).upsert(eq(user.getId()), eq(MediaType.EPISODE.name()), eq(show.getId()),
                episodeId.capture(), any(), any(), any(), any(), any());
        return episodeId.getValue();
    }

    private WatchStatusEntity episodeStatus(EpisodeEntity episode, boolean watched) {
        return WatchStatusEntity.builder()
                .userEntity(user)
                .playQueueItemId(UUID.randomUUID())
                .episodeEntity(episode)
                .watched(watched)
                .build();
    }

    private EpisodeEntity episode(int seasonNumber, int number) {
        SeasonEntity season = SeasonEntity.builder().number(seasonNumber).build();
        season.setId(UUID.randomUUID());
        EpisodeEntity episode = EpisodeEntity.builder()
                .showEntity(show).seasonEntity(season).number(number).build();
        episode.setId(UUID.randomUUID());
        return episode;
    }

    private ChapterEntity chapter(BookEntity book, int number) {
        ChapterEntity chapter = ChapterEntity.builder().bookEntity(book).number(number).build();
        chapter.setId(UUID.randomUUID());
        return chapter;
    }

    private static ShowEntity show() {
        ShowEntity show = ShowEntity.builder().name("Test Show").releaseYear(2024).build();
        show.setId(UUID.randomUUID());
        return show;
    }

    private static BookEntity book() {
        BookEntity book = BookEntity.builder().name("Test Book").build();
        book.setId(UUID.randomUUID());
        return book;
    }

    private static MovieEntity movie() {
        MovieEntity movie = MovieEntity.builder().name("Test Movie").releaseYear(2024).build();
        movie.setId(UUID.randomUUID());
        return movie;
    }

    private static UserEntity user() {
        UserEntity user = UserEntity.builder().name("test-user").externalId("sub-123").build();
        user.setId(UUID.randomUUID());
        return user;
    }

    private static WatchStatusRepository.RecentEntry recentEntry(UUID itemId, UUID groupId, Instant lastWatched,
                                                                 boolean watched) {
        return new WatchStatusRepository.RecentEntry() {
            @Override
            public UUID getItemId() {
                return itemId;
            }

            @Override
            public UUID getGroupId() {
                return groupId;
            }

            @Override
            public Instant getLastWatched() {
                return lastWatched;
            }

            @Override
            public boolean getWatched() {
                return watched;
            }

            @Override
            public long getProgressInMilliseconds() {
                return 0;
            }

            @Override
            public Double getReadingProgress() {
                return null;
            }
        };
    }
}
