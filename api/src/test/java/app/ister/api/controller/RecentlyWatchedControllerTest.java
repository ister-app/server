package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.ContinueWatchingEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.service.ContinueWatchingService;
import app.ister.core.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The controller only renders the precomputed entries (what to resume with is decided by
 * ContinueWatchingService), so this covers the mapping onto the GraphQL type.
 */
@ExtendWith(MockitoExtension.class)
class RecentlyWatchedControllerTest {

    @Mock
    private app.ister.core.service.LibraryAccessService libraryAccessService;

    @InjectMocks
    private RecentlyWatchedController subject;

    @Mock
    private ContinueWatchingService continueWatchingService;

    @Mock
    private UserService userService;

    @Mock
    private Authentication authentication;

    private final UserEntity user = user();

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(libraryAccessService.canAccess(
                org.mockito.ArgumentMatchers.<app.ister.core.entity.LibraryEntity>any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        lenient().when(authentication.getName()).thenReturn("sub-123");
        lenient().when(userService.getOrCreateUser(authentication)).thenReturn(user);
    }

    @Test
    void everyEntryTypeIsRenderedWithItsMedia() {
        EpisodeEntity episode = EpisodeEntity.builder().number(3).build();
        episode.setId(UUID.randomUUID());
        MovieEntity movie = MovieEntity.builder().name("Movie").releaseYear(2024).build();
        movie.setId(UUID.randomUUID());
        BookEntity book = BookEntity.builder().name("Book").build();
        book.setId(UUID.randomUUID());
        ChapterEntity chapter = ChapterEntity.builder().number(2).bookEntity(book).build();
        chapter.setId(UUID.randomUUID());
        PodcastEpisodeEntity podcastEpisode = PodcastEpisodeEntity.builder().guid("guid-1").build();
        podcastEpisode.setId(UUID.randomUUID());

        ContinueWatchingEntity episodeEntry = entry(MediaType.EPISODE, minutesAgo(1));
        episodeEntry.setEpisodeEntity(episode);
        ContinueWatchingEntity movieEntry = entry(MediaType.MOVIE, minutesAgo(2));
        movieEntry.setMovieEntity(movie);
        ContinueWatchingEntity chapterEntry = entry(MediaType.CHAPTER, minutesAgo(3));
        chapterEntry.setChapterEntity(chapter);
        ContinueWatchingEntity bookEntry = entry(MediaType.BOOK, minutesAgo(4));
        bookEntry.setBookEntity(book);
        ContinueWatchingEntity podcastEntry = entry(MediaType.PODCAST_EPISODE, minutesAgo(5));
        podcastEntry.setPodcastEpisodeEntity(podcastEpisode);

        when(continueWatchingService.entriesFor(user.getId()))
                .thenReturn(List.of(episodeEntry, movieEntry, chapterEntry, bookEntry, podcastEntry));

        List<RecentlyWatched> result = subject.recentlyWatched(authentication);

        assertEquals(List.of(MediaType.EPISODE, MediaType.MOVIE, MediaType.CHAPTER, MediaType.BOOK,
                        MediaType.PODCAST_EPISODE),
                result.stream().map(RecentlyWatched::type).toList(),
                "the order of the entries is kept as the service returned them");
        assertSame(episode, result.get(0).episode());
        assertSame(movie, result.get(1).movie());
        assertSame(chapter, result.get(2).chapter());
        assertSame(book, result.get(2).book(), "a chapter carries its book");
        assertSame(book, result.get(3).book());
        assertSame(podcastEpisode, result.get(4).podcastEpisode());
    }

    /** A book is one entry that can resume both the epub and the audiobook. */
    @Test
    void aBookEntryCarriesBothReadingAndListeningTargets() {
        BookEntity book = BookEntity.builder().name("Book").build();
        book.setId(UUID.randomUUID());
        ChapterEntity chapter = ChapterEntity.builder().number(2).bookEntity(book).build();
        chapter.setId(UUID.randomUUID());
        ContinueWatchingEntity bookEntry = entry(MediaType.BOOK, minutesAgo(1));
        bookEntry.setBookEntity(book);
        bookEntry.setChapterEntity(chapter);
        when(continueWatchingService.entriesFor(user.getId())).thenReturn(List.of(bookEntry));

        RecentlyWatched result = subject.recentlyWatched(authentication).getFirst();

        assertEquals(MediaType.BOOK, result.type());
        assertSame(book, result.book());
        assertSame(chapter, result.chapter());
    }

    /** Only the audiobook was played: the book is derived from the chapter so the tile still has one. */
    @Test
    void aBookOnlyListenedToStillCarriesItsBook() {
        BookEntity book = BookEntity.builder().name("Book").build();
        book.setId(UUID.randomUUID());
        ChapterEntity chapter = ChapterEntity.builder().number(2).bookEntity(book).build();
        chapter.setId(UUID.randomUUID());
        ContinueWatchingEntity bookEntry = entry(MediaType.BOOK, minutesAgo(1));
        bookEntry.setChapterEntity(chapter);
        when(continueWatchingService.entriesFor(user.getId())).thenReturn(List.of(bookEntry));

        RecentlyWatched result = subject.recentlyWatched(authentication).getFirst();

        assertEquals(MediaType.BOOK, result.type());
        assertSame(chapter, result.chapter());
        assertSame(book, result.book(), "derived from the chapter");
    }

    /** A book entry with neither slot set (both formats finished) is left out. */
    @Test
    void aBookEntryWithNothingToResumeIsLeftOut() {
        when(continueWatchingService.entriesFor(user.getId()))
                .thenReturn(List.of(entry(MediaType.BOOK, minutesAgo(1))));

        assertTrue(subject.recentlyWatched(authentication).isEmpty());
    }

    /** The media was deleted after the entry was written; the foreign key nulled the reference out. */
    @Test
    void entryWhoseMediaIsGoneIsLeftOut() {
        when(continueWatchingService.entriesFor(user.getId()))
                .thenReturn(List.of(entry(MediaType.EPISODE, minutesAgo(1))));

        assertTrue(subject.recentlyWatched(authentication).isEmpty());
    }

    private ContinueWatchingEntity entry(MediaType type, Instant lastWatched) {
        ContinueWatchingEntity entry = ContinueWatchingEntity.builder()
                .userEntity(user)
                .entryType(type)
                .groupId(UUID.randomUUID())
                .lastWatched(lastWatched)
                .build();
        entry.setId(UUID.randomUUID());
        return entry;
    }

    private static Instant minutesAgo(int minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES);
    }

    private static UserEntity user() {
        UserEntity user = UserEntity.builder().name("test-user").externalId("sub-123").build();
        user.setId(UUID.randomUUID());
        return user;
    }
}
