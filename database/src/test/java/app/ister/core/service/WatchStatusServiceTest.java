package app.ister.core.service;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.repository.WatchStatusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatchStatusServiceTest {

    @InjectMocks
    private WatchStatusService subject;

    @Mock
    private UserService userService;

    @Mock
    private WatchStatusRepository watchStatusRepository;

    @Mock
    private Authentication authentication;

    @Test
    void getOrCreateReturnsExisting() {
        UserEntity user = UserEntity.builder().build();
        EpisodeEntity episode = EpisodeEntity.builder().build();
        UUID playQueueItemId = UUID.randomUUID();
        WatchStatusEntity existing = WatchStatusEntity.builder().userEntity(user).build();

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findByUserEntityAndPlayQueueItemIdAndEpisodeEntity(user, playQueueItemId, episode))
                .thenReturn(Optional.of(existing));

        WatchStatusEntity result = subject.getOrCreate(authentication, playQueueItemId, episode, null);

        assertEquals(existing, result);
    }

    @Test
    void getOrCreateCreatesNew() {
        UserEntity user = UserEntity.builder().build();
        EpisodeEntity episode = EpisodeEntity.builder().build();
        UUID playQueueItemId = UUID.randomUUID();

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findByUserEntityAndPlayQueueItemIdAndEpisodeEntity(user, playQueueItemId, episode))
                .thenReturn(Optional.empty());

        WatchStatusEntity result = subject.getOrCreate(authentication, playQueueItemId, episode, null);

        assertFalse(result.isWatched());
        verify(watchStatusRepository).save(any(WatchStatusEntity.class));
    }

    /**
     * Listening progress must not be scoped to a play queue: a second queue over the same book has
     * to find the row the first one wrote, and the reader (which has no queue at all) writes it too.
     */
    @Test
    void getOrCreateForChapterIsSharedAcrossPlayQueues() {
        UserEntity user = UserEntity.builder().build();
        ChapterEntity chapter = ChapterEntity.builder().build();
        chapter.setId(UUID.randomUUID());
        WatchStatusEntity existing = WatchStatusEntity.builder().userEntity(user).chapterEntity(chapter).build();

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findByUserEntityAndChapterEntity(user, chapter))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateForChapter(authentication, chapter));
    }

    @Test
    void getOrCreateForChapterKeysNewRowsOnTheChapter() {
        UserEntity user = UserEntity.builder().build();
        ChapterEntity chapter = ChapterEntity.builder().build();
        chapter.setId(UUID.randomUUID());

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findByUserEntityAndChapterEntity(user, chapter)).thenReturn(Optional.empty());
        when(watchStatusRepository.save(any(WatchStatusEntity.class))).thenAnswer(call -> call.getArgument(0));

        WatchStatusEntity result = subject.getOrCreateForChapter(authentication, chapter);

        assertEquals(chapter.getId(), result.getPlayQueueItemId());
        assertFalse(result.isWatched());
    }

    @Test
    void getOrCreateForPodcastEpisodeReturnsExisting() {
        UserEntity user = UserEntity.builder().build();
        PodcastEpisodeEntity episode = PodcastEpisodeEntity.builder().build();
        UUID playQueueItemId = UUID.randomUUID();
        WatchStatusEntity existing = WatchStatusEntity.builder().userEntity(user).podcastEpisodeEntity(episode).build();

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findByUserEntityAndPlayQueueItemIdAndPodcastEpisodeEntity(user, playQueueItemId, episode))
                .thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateForPodcastEpisode(authentication, playQueueItemId, episode));
        verify(watchStatusRepository, never()).save(any(WatchStatusEntity.class));
    }

    @Test
    void getOrCreateForPodcastEpisodeCreatesNew() {
        UserEntity user = UserEntity.builder().build();
        PodcastEpisodeEntity episode = PodcastEpisodeEntity.builder().build();
        UUID playQueueItemId = UUID.randomUUID();

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findByUserEntityAndPlayQueueItemIdAndPodcastEpisodeEntity(user, playQueueItemId, episode))
                .thenReturn(Optional.empty());
        when(watchStatusRepository.save(any(WatchStatusEntity.class))).thenAnswer(call -> call.getArgument(0));

        WatchStatusEntity result = subject.getOrCreateForPodcastEpisode(authentication, playQueueItemId, episode);

        assertEquals(playQueueItemId, result.getPlayQueueItemId());
        assertEquals(episode, result.getPodcastEpisodeEntity());
        assertFalse(result.isWatched());
    }

    @Test
    void getOrCreateForBookReturnsExisting() {
        UserEntity user = UserEntity.builder().build();
        BookEntity book = BookEntity.builder().build();
        book.setId(UUID.randomUUID());
        WatchStatusEntity existing = WatchStatusEntity.builder().userEntity(user).bookEntity(book).build();

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findByUserEntityAndBookEntity(user, book)).thenReturn(Optional.of(existing));

        assertEquals(existing, subject.getOrCreateForBook(authentication, book));
        verify(watchStatusRepository, never()).save(any(WatchStatusEntity.class));
    }

    /**
     * Reading has no play queue, so the book id doubles as the play queue item id.
     */
    @Test
    void getOrCreateForBookKeysNewRowsOnTheBook() {
        UserEntity user = UserEntity.builder().build();
        BookEntity book = BookEntity.builder().build();
        book.setId(UUID.randomUUID());

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findByUserEntityAndBookEntity(user, book)).thenReturn(Optional.empty());
        when(watchStatusRepository.save(any(WatchStatusEntity.class))).thenAnswer(call -> call.getArgument(0));

        WatchStatusEntity result = subject.getOrCreateForBook(authentication, book);

        assertEquals(book.getId(), result.getPlayQueueItemId());
        assertEquals(book, result.getBookEntity());
        assertFalse(result.isWatched());
    }

    @Test
    void getOrCreateWithMovieEntity() {
        UserEntity user = UserEntity.builder().build();
        MovieEntity movie = MovieEntity.builder().build();
        UUID playQueueItemId = UUID.randomUUID();

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findByUserEntityAndPlayQueueItemIdAndEpisodeEntity(user, playQueueItemId, null))
                .thenReturn(Optional.empty());

        WatchStatusEntity result = subject.getOrCreate(authentication, playQueueItemId, null, movie);

        assertFalse(result.isWatched());
        verify(watchStatusRepository).save(any(WatchStatusEntity.class));
    }
}
