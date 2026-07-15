package app.ister.core.service;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.ContinueWatchingEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.MediaType;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the continue-watching cache the way playback does, against a real PostgreSQL: the point of
 * the whole feature is that finishing an episode puts the <em>next</em> one in the user's list, and
 * only the real database can prove that (the next-up lookup and the upsert are native SQL).
 * Skipped when no container runtime (docker/podman socket) is available.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
// Flyway is not part of the @DataJpaTest slice in Spring Boot 4, import it explicitly
@org.springframework.boot.autoconfigure.ImportAutoConfiguration(org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// PersistenceConfig (@EnableJpaAuditing) is filtered out of the slice; needed for dateCreated/dateUpdated
@org.springframework.context.annotation.Import({app.ister.core.config.PersistenceConfig.class,
        ContinueWatchingService.class})
@Testcontainers(disabledWithoutDocker = true)
class ContinueWatchingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private TestEntityManager em;

    @Autowired
    private ContinueWatchingService subject;

    private UserEntity user;
    private ShowEntity show;
    private EpisodeEntity episode1;
    private EpisodeEntity episode2;

    @BeforeEach
    void setUp() {
        user = em.persist(UserEntity.builder().externalId("user-" + UUID.randomUUID()).build());
        LibraryEntity library = em.persist(LibraryEntity.builder()
                .libraryType(LibraryType.SHOW).name("Shows-" + UUID.randomUUID()).build());
        show = em.persist(ShowEntity.builder().libraryEntity(library).name("Show").releaseYear(2020).build());
        SeasonEntity season = em.persist(SeasonEntity.builder().showEntity(show).number(1).build());
        episode1 = em.persist(EpisodeEntity.builder().showEntity(show).seasonEntity(season).number(1).build());
        episode2 = em.persist(EpisodeEntity.builder().showEntity(show).seasonEntity(season).number(2).build());
        em.flush();
    }

    @Test
    void halfWayThroughAnEpisodeTheListResumesThatEpisode() {
        played(episode1, false);

        assertEquals(episode1.getId(), onlyEntry().getEpisodeEntity().getId());
    }

    /** The behaviour this whole cache exists for. */
    @Test
    void finishingAnEpisodeMovesTheListOnToTheNextOne() {
        WatchStatusEntity status = played(episode1, false);
        assertEquals(episode1.getId(), onlyEntry().getEpisodeEntity().getId());

        // The heartbeat reaches the end of the episode: watched flips to true.
        WatchStatusEntity finished = em.find(WatchStatusEntity.class, status.getId());
        finished.setWatched(true);
        em.flush();
        subject.onWatchStatusChanged(finished);
        em.flush();
        em.clear();

        ContinueWatchingEntity entry = onlyEntry();
        assertEquals(episode2.getId(), entry.getEpisodeEntity().getId(), "the next episode of the show");
        assertEquals(MediaType.EPISODE, entry.getEntryType());
        assertEquals(show.getId(), entry.getGroupId(), "still one entry for the show, not one per episode");
    }

    @Test
    void finishingTheLastEpisodeEmptiesTheListButKeepsTheEntry() {
        played(episode1, true);
        played(episode2, true);

        assertTrue(subject.entriesFor(user.getId()).isEmpty(), "nothing left to continue with");

        // A newly scanned episode brings the show back into the list.
        SeasonEntity season2 = em.persist(SeasonEntity.builder().showEntity(show).number(2).build());
        EpisodeEntity episode3 = em.persist(EpisodeEntity.builder()
                .showEntity(show).seasonEntity(season2).number(1).build());
        em.flush();

        subject.recomputeForShow(show.getId());
        em.flush();
        em.clear();

        assertEquals(episode3.getId(), onlyEntry().getEpisodeEntity().getId());
    }

    /** The nightly repair reaches the same conclusion as the heartbeat did, from the history alone. */
    @Test
    void rebuildReproducesTheListFromTheWatchHistory() {
        em.persistAndFlush(watchStatus(episode1, true));

        subject.rebuildForUser(user);
        em.flush();
        em.clear();

        assertEquals(episode2.getId(), onlyEntry().getEpisodeEntity().getId());
    }

    @Test
    void rebuildDropsEntriesTheWatchHistoryNoLongerSupports() {
        // An entry for a watch status that is not (or no longer) in the history.
        subject.onWatchStatusChanged(watchStatus(episode1, false));
        em.clear();
        assertEquals(1, subject.entriesFor(user.getId()).size());

        subject.rebuildForUser(user);
        em.flush();
        em.clear();

        assertTrue(subject.entriesFor(user.getId()).isEmpty());
    }

    /** All episodes of a podcast collapse to one entry, and finishing one hands over to the next. */
    @Test
    void podcastEpisodesGroupUnderTheirPodcastAndHandOver() {
        LibraryEntity library = em.persist(LibraryEntity.builder()
                .libraryType(LibraryType.PODCAST).name("Podcasts-" + UUID.randomUUID()).build());
        PodcastEntity podcast = em.persist(PodcastEntity.builder()
                .libraryEntity(library).feedUrl("feed-" + UUID.randomUUID()).title("Pod").build());
        PodcastEpisodeEntity ep1 = em.persist(PodcastEpisodeEntity.builder()
                .podcastEntity(podcast).guid("g1").enclosureUrl("http://x/1.mp3")
                .publishedAt(Instant.parse("2025-01-01T00:00:00Z")).build());
        PodcastEpisodeEntity ep2 = em.persist(PodcastEpisodeEntity.builder()
                .podcastEntity(podcast).guid("g2").enclosureUrl("http://x/2.mp3")
                .publishedAt(Instant.parse("2025-01-08T00:00:00Z")).build());
        em.flush();

        // Two different episodes of the same podcast, both mid-way through: still one entry.
        playedPodcast(ep1, false);
        playedPodcast(ep2, false);
        ContinueWatchingEntity entry = onlyEntry();
        assertEquals(podcast.getId(), entry.getGroupId(), "one entry for the podcast, not one per episode");
        assertEquals(ep2.getId(), entry.getPodcastEpisodeEntity().getId(), "resumes the last one played");

        // Finishing ep1 hands the podcast over to the next unfinished episode by publish date.
        playedPodcast(ep1, true);
        assertEquals(ep2.getId(), onlyEntry().getPodcastEpisodeEntity().getId(),
                "the next unfinished episode by publish date");
    }

    /**
     * A book that is both read (epub) and listened to (audiobook) is a single entry with two
     * independent slots; writing one must not clear the other.
     */
    @Test
    void aBookReadAndListenedToIsOneEntryWithBothSlots() {
        LibraryEntity library = em.persist(LibraryEntity.builder()
                .libraryType(LibraryType.BOOK).name("Books-" + UUID.randomUUID()).build());
        PersonEntity author = em.persist(PersonEntity.builder().name("Author").build());
        BookEntity book = em.persist(BookEntity.builder()
                .libraryEntity(library).personEntity(author).name("Book").releaseYear(2021).build());
        ChapterEntity chapter1 = em.persist(ChapterEntity.builder()
                .bookEntity(book).personEntity(author).number(1).build());
        em.persist(ChapterEntity.builder().bookEntity(book).personEntity(author).number(2).build());
        em.flush();

        // Listen to chapter 1 (audio slot), then read the epub (reading slot).
        WatchStatusEntity listening = em.persistAndFlush(WatchStatusEntity.builder()
                .userEntity(user).playQueueItemId(chapter1.getId()).chapterEntity(chapter1)
                .progressInMilliseconds(30_000).watched(false).build());
        subject.onWatchStatusChanged(listening);
        em.flush();
        em.clear();

        WatchStatusEntity reading = em.persistAndFlush(WatchStatusEntity.builder()
                .userEntity(user).playQueueItemId(book.getId()).bookEntity(book)
                .readingProgress(0.3).watched(false).build());
        subject.onWatchStatusChanged(reading);
        em.flush();
        em.clear();

        ContinueWatchingEntity entry = onlyEntry();
        assertEquals(MediaType.BOOK, entry.getEntryType());
        assertEquals(book.getId(), entry.getGroupId(), "one entry for the book, not one per format");
        assertEquals(chapter1.getId(), entry.getChapterEntity().getId(), "audio slot still set after reading");
        assertEquals(book.getId(), entry.getBookEntity().getId(), "reading slot set");
    }

    private WatchStatusEntity playedPodcast(PodcastEpisodeEntity episode, boolean watched) {
        WatchStatusEntity status = em.persistAndFlush(WatchStatusEntity.builder()
                .userEntity(user)
                .playQueueItemId(UUID.randomUUID())
                .podcastEpisodeEntity(episode)
                .progressInMilliseconds(120_000)
                .watched(watched)
                .build());
        subject.onWatchStatusChanged(status);
        em.flush();
        em.clear();
        return status;
    }

    /** Plays an episode the way the heartbeat does: persist the watch status, then update the cache. */
    private WatchStatusEntity played(EpisodeEntity episode, boolean watched) {
        WatchStatusEntity status = em.persistAndFlush(watchStatus(episode, watched));
        subject.onWatchStatusChanged(status);
        em.flush();
        em.clear();
        return status;
    }

    private WatchStatusEntity watchStatus(EpisodeEntity episode, boolean watched) {
        return WatchStatusEntity.builder()
                .userEntity(user)
                .playQueueItemId(UUID.randomUUID())
                .episodeEntity(episode)
                .watched(watched)
                .build();
    }

    private ContinueWatchingEntity onlyEntry() {
        List<ContinueWatchingEntity> entries = subject.entriesFor(user.getId());
        assertEquals(1, entries.size(), "one entry per show");
        return entries.get(0);
    }
}
