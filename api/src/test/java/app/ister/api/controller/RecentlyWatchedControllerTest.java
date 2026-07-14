package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.BookResumeService;
import app.ister.core.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecentlyWatchedControllerTest {

    @InjectMocks
    private RecentlyWatchedController subject;

    @Mock
    private EpisodeRepository episodeRepository;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private BookResumeService bookResumeService;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private PodcastEpisodeRepository podcastEpisodeRepository;

    @Mock
    private WatchStatusRepository watchStatusRepository;

    @Mock
    private UserService userService;

    @Mock
    private Authentication authentication;

    private UserEntity user() {
        UserEntity user = UserEntity.builder().name("test-user").externalId("sub-123").build();
        user.setId(UUID.randomUUID());
        return user;
    }

    private static WatchStatusEntity status(boolean watched, Instant updated) {
        WatchStatusEntity status = WatchStatusEntity.builder().watched(watched).build();
        status.setDateUpdated(updated);
        return status;
    }

    @Test
    void recentlyWatchedReturnsCurrentEpisodeWhenNotWatched() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity unwatched = WatchStatusEntity.builder().watched(false).build();
        unwatched.setDateUpdated(Instant.now());
        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(unwatched))).build();
        ep1.setId(ep1Id);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of());
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(MediaType.EPISODE, result.get(0).type());
        assertEquals(ep1, result.get(0).episode());
        assertNull(result.get(0).movie());
    }

    @Test
    void recentlyWatchedReturnsNextEpisodeWhenCurrentIsWatched() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID ep2Id = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity watched = WatchStatusEntity.builder().watched(true).build();
        watched.setDateUpdated(Instant.now());

        WatchStatusEntity unwatched = WatchStatusEntity.builder().watched(false).build();
        unwatched.setDateUpdated(Instant.now());

        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(watched))).build();
        ep1.setId(ep1Id);
        EpisodeEntity ep2 = EpisodeEntity.builder().number(2).watchStatusEntities(new ArrayList<>(List.of(unwatched))).build();
        ep2.setId(ep2Id);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of());
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1, ep2));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(MediaType.EPISODE, result.get(0).type());
        assertEquals(ep2, result.get(0).episode());
    }

    @Test
    void recentlyWatchedReturnsOldWatchedEpisode() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID ep2Id = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity watched = WatchStatusEntity.builder().watched(true).build();
        watched.setDateUpdated(Instant.now());

        WatchStatusEntity oldWatched = WatchStatusEntity.builder().watched(true).build();
        oldWatched.setDateUpdated(Instant.now().minus(301, ChronoUnit.DAYS));

        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(watched))).build();
        ep1.setId(ep1Id);
        EpisodeEntity ep2 = EpisodeEntity.builder().number(2).watchStatusEntities(new ArrayList<>(List.of(oldWatched))).build();
        ep2.setId(ep2Id);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of());
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1, ep2));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(ep2, result.get(0).episode());
    }

    @Test
    void recentlyWatchedReturnsEmptyWhenAllEpisodesWatched() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity watched = WatchStatusEntity.builder().watched(true).build();
        watched.setDateUpdated(Instant.now());

        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(watched))).build();
        ep1.setId(ep1Id);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of());
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertTrue(result.isEmpty());
    }

    @Test
    void recentlyWatchedReturnsUnwatchedMovie() {
        UUID movieId = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        MovieEntity movie = MovieEntity.builder().name("Test Movie").releaseYear(2020)
                .watchStatusEntities(new ArrayList<>()).build();
        movie.setId(movieId);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId())).thenReturn(List.of());
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of(movieId.toString()));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(MediaType.MOVIE, result.get(0).type());
        assertEquals(movie, result.get(0).movie());
        assertNull(result.get(0).episode());
    }

    @Test
    void recentlyWatchedExcludesFullyWatchedRecentMovie() {
        UUID movieId = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity watched = WatchStatusEntity.builder().watched(true).build();
        watched.setDateUpdated(Instant.now());

        MovieEntity movie = MovieEntity.builder().name("Test Movie").releaseYear(2020)
                .watchStatusEntities(new ArrayList<>(List.of(watched))).build();
        movie.setId(movieId);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId())).thenReturn(List.of());
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of(movieId.toString()));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertTrue(result.isEmpty());
    }

    @Test
    void recentlyWatchedIncludesOldWatchedMovie() {
        UUID movieId = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity oldWatched = WatchStatusEntity.builder().watched(true).build();
        oldWatched.setDateUpdated(Instant.now().minus(301, ChronoUnit.DAYS));

        MovieEntity movie = MovieEntity.builder().name("Test Movie").releaseYear(2020)
                .watchStatusEntities(new ArrayList<>(List.of(oldWatched))).build();
        movie.setId(movieId);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId())).thenReturn(List.of());
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of(movieId.toString()));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(movie, result.get(0).movie());
    }

    @Test
    void recentlyWatchedSortsMovieBeforeEpisodeWhenMovieIsMoreRecent() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID movieId = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        // Episode watched 2 hours ago
        WatchStatusEntity epWatched = WatchStatusEntity.builder().watched(false).build();
        epWatched.setDateUpdated(Instant.now().minus(2, ChronoUnit.HOURS));
        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(epWatched))).build();
        ep1.setId(ep1Id);

        // Movie watched 1 hour ago (more recent)
        WatchStatusEntity movieWatched = WatchStatusEntity.builder().watched(false).build();
        movieWatched.setDateUpdated(Instant.now().minus(1, ChronoUnit.HOURS));
        MovieEntity movie = MovieEntity.builder().name("Test Movie").releaseYear(2020)
                .watchStatusEntities(new ArrayList<>(List.of(movieWatched))).build();
        movie.setId(movieId);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of(movieId.toString()));
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class))).thenReturn(List.of(ep1));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(2, result.size());
        assertEquals(MediaType.MOVIE, result.get(0).type());
        assertEquals(MediaType.EPISODE, result.get(1).type());
    }

    @Test
    void recentlyWatchedSortsEpisodeBeforeMovieWhenEpisodeIsMoreRecent() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID movieId = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        // Episode watched 30 minutes ago (more recent)
        WatchStatusEntity epWatched = WatchStatusEntity.builder().watched(false).build();
        epWatched.setDateUpdated(Instant.now().minus(30, ChronoUnit.MINUTES));
        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(epWatched))).build();
        ep1.setId(ep1Id);

        // Movie watched 2 hours ago
        WatchStatusEntity movieWatched = WatchStatusEntity.builder().watched(false).build();
        movieWatched.setDateUpdated(Instant.now().minus(2, ChronoUnit.HOURS));
        MovieEntity movie = MovieEntity.builder().name("Test Movie").releaseYear(2020)
                .watchStatusEntities(new ArrayList<>(List.of(movieWatched))).build();
        movie.setId(movieId);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(watchStatusRepository.findRecentMovieIdsByUserId(user.getId())).thenReturn(List.of(movieId.toString()));
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class))).thenReturn(List.of(ep1));
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(2, result.size());
        assertEquals(MediaType.EPISODE, result.get(0).type());
        assertEquals(MediaType.MOVIE, result.get(1).type());
    }

    // --- continue listening / reading ---

    /** The audiobook continues at the chapter the resume service picked, on the book it belongs to. */
    @Test
    void recentlyWatchedReturnsTheChapterToResumeAt() {
        UserEntity user = user();
        BookEntity book = BookEntity.builder().name("Dit zijn de namen").build();
        book.setId(UUID.randomUUID());
        ChapterEntity chapter1 = ChapterEntity.builder().number(1).bookEntity(book).build();
        chapter1.setId(UUID.randomUUID());
        ChapterEntity chapter2 = ChapterEntity.builder().number(2).bookEntity(book).build();
        chapter2.setId(UUID.randomUUID());

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentChaptersAndBookIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{chapter1.getId().toString(), book.getId().toString()}));
        when(bookResumeService.resume(user, book.getId()))
                .thenReturn(Optional.of(new BookResumeService.ChapterResume(chapter2, Instant.now())));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(MediaType.CHAPTER, result.getFirst().type());
        assertEquals(chapter2, result.getFirst().chapter());
        assertEquals(book, result.getFirst().book());
    }

    /** Nothing left to resume — a finished audiobook — drops out of the list. */
    @Test
    void recentlyWatchedDropsAFinishedAudiobook() {
        UserEntity user = user();
        BookEntity book = BookEntity.builder().name("Dit zijn de namen").build();
        book.setId(UUID.randomUUID());
        ChapterEntity chapter = ChapterEntity.builder().number(1).bookEntity(book).build();
        chapter.setId(UUID.randomUUID());

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentChaptersAndBookIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{chapter.getId().toString(), book.getId().toString()}));
        when(bookResumeService.resume(user, book.getId())).thenReturn(Optional.empty());

        assertTrue(subject.recentlyWatched(authentication).isEmpty());
    }

    @Test
    void recentlyWatchedReturnsAPartiallyReadBook() {
        UserEntity user = user();
        BookEntity book = BookEntity.builder().name("Dit zijn de namen").build();
        book.setId(UUID.randomUUID());
        WatchStatusEntity reading = status(false, Instant.now());
        reading.setReadingProgress(0.3);

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentBookIdsByUserId(user.getId()))
                .thenReturn(List.of(book.getId().toString()));
        when(bookRepository.findById(book.getId())).thenReturn(Optional.of(book));
        when(watchStatusRepository.findByUserEntityAndBookEntity(user, book)).thenReturn(Optional.of(reading));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(MediaType.BOOK, result.getFirst().type());
        assertEquals(book, result.getFirst().book());
        assertNull(result.getFirst().chapter());
    }

    @Test
    void recentlyWatchedDropsAFinishedBook() {
        UserEntity user = user();
        BookEntity book = BookEntity.builder().name("Dit zijn de namen").build();
        book.setId(UUID.randomUUID());
        WatchStatusEntity read = status(true, Instant.now());
        read.setReadingProgress(1.0);

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentBookIdsByUserId(user.getId()))
                .thenReturn(List.of(book.getId().toString()));
        when(bookRepository.findById(book.getId())).thenReturn(Optional.of(book));
        when(watchStatusRepository.findByUserEntityAndBookEntity(user, book)).thenReturn(Optional.of(read));

        assertTrue(subject.recentlyWatched(authentication).isEmpty());
    }

    @Test
    void recentlyWatchedReturnsAStartedPodcastEpisode() {
        UserEntity user = user();
        PodcastEntity podcast = PodcastEntity.builder().title("Serial")
                .feedUrl("https://example.org/feed").build();
        podcast.setId(UUID.randomUUID());
        PodcastEpisodeEntity episode = PodcastEpisodeEntity.builder().podcastEntity(podcast)
                .guid("ep-1").enclosureUrl("https://example.org/ep-1.mp3").build();
        episode.setId(UUID.randomUUID());
        WatchStatusEntity started = status(false, Instant.now());
        started.setProgressInMilliseconds(60_000L);

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentPodcastEpisodeIdsByUserId(user.getId()))
                .thenReturn(List.of(episode.getId().toString()));
        when(podcastEpisodeRepository.findById(episode.getId())).thenReturn(Optional.of(episode));
        when(watchStatusRepository.findByUserEntityExternalIdAndPodcastEpisodeEntityIn(eq("sub-123"),
                eq(List.of(episode)), any(Sort.class))).thenReturn(List.of(started));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(MediaType.PODCAST_EPISODE, result.getFirst().type());
        assertEquals(episode, result.getFirst().podcastEpisode());
    }

    /** A podcast episode that was played to the end is not something to continue. */
    @Test
    void recentlyWatchedDropsAFinishedPodcastEpisode() {
        UserEntity user = user();
        PodcastEntity podcast = PodcastEntity.builder().title("Serial")
                .feedUrl("https://example.org/feed").build();
        podcast.setId(UUID.randomUUID());
        PodcastEpisodeEntity episode = PodcastEpisodeEntity.builder().podcastEntity(podcast)
                .guid("ep-1").enclosureUrl("https://example.org/ep-1.mp3").build();
        episode.setId(UUID.randomUUID());
        WatchStatusEntity finished = status(true, Instant.now());
        finished.setProgressInMilliseconds(60_000L);

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentPodcastEpisodeIdsByUserId(user.getId()))
                .thenReturn(List.of(episode.getId().toString()));
        when(podcastEpisodeRepository.findById(episode.getId())).thenReturn(Optional.of(episode));
        when(watchStatusRepository.findByUserEntityExternalIdAndPodcastEpisodeEntityIn(eq("sub-123"),
                eq(List.of(episode)), any(Sort.class))).thenReturn(List.of(finished));

        assertTrue(subject.recentlyWatched(authentication).isEmpty());
    }

    @Test
    void schemaMappingsExposeTheItemsMedia() {
        EpisodeEntity episode = EpisodeEntity.builder().number(1).build();
        MovieEntity movie = MovieEntity.builder().name("Heat").releaseYear(1995).build();
        BookEntity book = BookEntity.builder().name("Dit zijn de namen").build();
        ChapterEntity chapter = ChapterEntity.builder().number(1).bookEntity(book).build();
        PodcastEpisodeEntity podcastEpisode = PodcastEpisodeEntity.builder()
                .guid("ep-1").enclosureUrl("https://example.org/ep-1.mp3").build();
        Instant now = Instant.now();

        assertEquals(episode, subject.episode(RecentlyWatched.ofEpisode(episode, now)));
        assertEquals(movie, subject.movie(RecentlyWatched.ofMovie(movie, now)));
        assertEquals(chapter, subject.chapter(RecentlyWatched.ofChapter(chapter, now)));
        assertEquals(book, subject.book(RecentlyWatched.ofBook(book, now)));
        assertEquals(podcastEpisode,
                subject.podcastEpisode(RecentlyWatched.ofPodcastEpisode(podcastEpisode, now)));
    }
}
