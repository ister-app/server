package app.ister.core.repository;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.ContinueWatchingEntity;
import app.ister.core.entity.CreditEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileSegmentEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.enums.CreditType;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.SegmentType;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.enums.StreamCodecType;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the Flyway migrations against a real PostgreSQL and exercises the repository
 * methods that H2/mocks can never validate: schema-entity match (ddl-auto=validate)
 * and the PostgreSQL-specific native queries. Skipped when no container runtime
 * (docker/podman socket) is available.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
// Flyway is not part of the @DataJpaTest slice in Spring Boot 4, import it explicitly
@org.springframework.boot.autoconfigure.ImportAutoConfiguration(org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// PersistenceConfig (@EnableJpaAuditing) is filtered out of the slice; needed for dateCreated/dateUpdated
@org.springframework.context.annotation.Import(app.ister.core.config.PersistenceConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class PostgresRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private TestEntityManager em;

    @Autowired
    private WatchStatusRepository watchStatusRepository;

    @Autowired
    private ContinueWatchingRepository continueWatchingRepository;

    @Autowired
    private MediaFileStreamRepository mediaFileStreamRepository;

    @Autowired
    private MediaFileSegmentRepository mediaFileSegmentRepository;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private ImageRepository imageRepository;

    @Autowired
    private RatingRepository ratingRepository;

    @Autowired
    private SeriesRepository seriesRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private CreditRepository creditRepository;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private PodcastRepository podcastRepository;

    /**
     * The blur-hash sweep walks a directory in chunks, resuming from the last id of the previous
     * chunk. Note that PostgreSQL orders {@code uuid} as unsigned bytes while
     * {@link UUID#compareTo} compares signed longs, so the two disagree: both the {@code ORDER BY}
     * and the {@code id >} of the cursor must be evaluated by the database. Never compare the
     * cursor in Java.
     */
    @Test
    void blurHashSweepChunksResumeAfterCursorAndSkipUnhashableImages() {
        DirectoryEntity directory = persistDirectory("blur-sweep");
        DirectoryEntity otherDirectory = persistDirectory("blur-sweep-other");

        // Six images without a blur-hash, one with. Ids are random UUIDs, so the id ordering
        // the sweep relies on is not the insertion order -- exactly as in production.
        IntStream.range(0, 6).forEach(i -> persistImage(directory, "/cache/no-hash-" + i + ".jpg", null));
        persistImage(directory, "/cache/hashed.jpg", "LEHV6nWB2yk8pyo0adR*");
        persistImage(otherDirectory, "/cache/other-directory.jpg", null);
        em.flush();

        List<ImageEntity> first = imageRepository
                .findByDirectoryEntityIdAndBlurHashIsNullOrderById(directory.getId(), Limit.of(4));
        assertEquals(4, first.size());
        assertTrue(first.stream().allMatch(i -> i.getDirectoryEntityId().equals(directory.getId())),
                "sweep must not leak images from another directory");
        assertEquals(first, imageRepository.findByDirectoryEntityIdAndBlurHashIsNullOrderById(
                directory.getId(), Limit.of(4)), "chunk order must be deterministic across calls");

        // Resuming after the last id of the previous chunk yields the remainder, without overlap.
        List<ImageEntity> second = imageRepository.findByDirectoryEntityIdAndBlurHashIsNullAndIdGreaterThanOrderById(
                directory.getId(), first.getLast().getId(), Limit.of(4));
        assertEquals(2, second.size());
        assertTrue(second.stream().noneMatch(first::contains), "chunks must not overlap");

        // Together the chunks cover every unhashed image in the directory exactly once, and the
        // already-hashed one is never revisited.
        assertEquals(6, new HashSet<>(concat(first, second)).size());
        assertTrue(concat(first, second).stream().noneMatch(i -> i.getPath().equals("/cache/hashed.jpg")));

        // Cursor past the final id terminates the sweep, even though the images it skipped are
        // still blur-hash-less (the CMYK case): an empty chunk is what stops the chain.
        assertTrue(imageRepository.findByDirectoryEntityIdAndBlurHashIsNullAndIdGreaterThanOrderById(
                directory.getId(), second.getLast().getId(), Limit.of(4)).isEmpty());
    }

    /**
     * A message published before the sweep became chunked carries no directory, so it deserialises
     * with a null id. Such a message must consume itself into an empty chunk rather than blow up or
     * sweep every directory at once.
     */
    @Test
    void chunkForAnUnknownDirectoryIsEmpty() {
        persistImage(persistDirectory("blur-sweep-legacy"), "/cache/legacy.jpg", null);
        em.flush();

        assertTrue(imageRepository.findByDirectoryEntityIdAndBlurHashIsNullOrderById(null, Limit.of(500)).isEmpty());
    }

    private static List<ImageEntity> concat(List<ImageEntity> a, List<ImageEntity> b) {
        List<ImageEntity> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private DirectoryEntity persistDirectory(String name) {
        NodeEntity node = em.persist(NodeEntity.builder().name("node-" + name).url("http://localhost").build());
        return em.persist(DirectoryEntity.builder()
                .nodeEntity(node).name(name).path("/data/" + name).directoryType(DirectoryType.CACHE).build());
    }

    private void persistImage(DirectoryEntity directory, String path, String blurHash) {
        ImageEntity image = ImageEntity.builder().type(ImageType.COVER).path(path).blurHash(blurHash).build();
        image.setDirectoryEntity(directory);
        em.persist(image);
    }

    /**
     * The comic insert-ignore upserts target the partial unique indexes of V23
     * ({@code ... WHERE person_entity_id IS NULL}) — syntax only a real PostgreSQL can validate.
     * First insert reports 1 (created), the racing duplicate reports 0 and leaves the row alone.
     */
    @Test
    void comicInsertIgnoreUpsertsReportInsertedVersusLostRace() {
        LibraryEntity library = em.persist(LibraryEntity.builder()
                .name("comics").libraryType(LibraryType.COMIC).build());
        em.flush();

        UUID seriesId = UUID.randomUUID();
        assertEquals(1, seriesRepository.insertComicSeriesIfAbsent(seriesId, library.getId(), "Attack on Titan", 2009));
        assertEquals(0, seriesRepository.insertComicSeriesIfAbsent(
                UUID.randomUUID(), library.getId(), "Attack on Titan", 2009), "duplicate must be a no-op");
        assertEquals(seriesId, seriesRepository
                .findByLibraryEntityAndNameAndStartYear(library, "Attack on Titan", 2009)
                .orElseThrow().getId(), "the first insert's row must survive the lost race");

        SeriesEntity series = seriesRepository.findById(seriesId).orElseThrow();
        UUID volumeId = UUID.randomUUID();
        assertEquals(1, bookRepository.insertComicVolumeIfAbsent(
                volumeId, library.getId(), seriesId, "aot_vol27", "Volume 27", 27.0, 0, 0));
        assertEquals(0, bookRepository.insertComicVolumeIfAbsent(
                UUID.randomUUID(), library.getId(), seriesId, "aot_vol27", "Volume 27", 27.0, 0, 0));
        assertEquals(volumeId, bookRepository
                .findBySeriesEntityAndNameAndPathYear(series, "aot_vol27", 0)
                .orElseThrow().getId());
    }

    @Test
    void flywayMigrationsMatchEntityMappings() {
        // Context startup already ran Flyway V1..Vn and validated the JPA mappings
        // against the migrated schema (ddl-auto=validate). Querying a migrated table
        // proves the schema is reachable and the mapping resolves against it.
        assertEquals(0, mediaFileStreamRepository.count());
    }

    @Test
    void findRecentEpisodeEntriesReturnsTheLastPlayedEpisodePerShow() {
        UserEntity user = em.persist(UserEntity.builder().externalId("user-1").build());
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.SHOW).name("Shows").build());
        ShowEntity show = em.persist(ShowEntity.builder().libraryEntity(library).name("Show").releaseYear(2020).build());
        SeasonEntity season = em.persist(SeasonEntity.builder().showEntity(show).number(1).build());
        EpisodeEntity episode1 = em.persist(EpisodeEntity.builder().showEntity(show).seasonEntity(season).number(1).build());
        EpisodeEntity episode2 = em.persist(EpisodeEntity.builder().showEntity(show).seasonEntity(season).number(2).build());
        em.persist(watchStatus(user, episode1, null));
        WatchStatusEntity latest = em.persist(watchStatus(user, episode2, null));
        // make episode2 the most recently updated watch status
        latest.setProgressInMilliseconds(1000);
        em.persistAndFlush(latest);

        List<WatchStatusRepository.RecentEntry> result =
                watchStatusRepository.findRecentEpisodeEntries(user.getId(), Instant.now().minus(Duration.ofDays(150)));

        assertEquals(1, result.size(), "one entry per show, not per watch status");
        assertEquals(episode2.getId(), result.get(0).getItemId());
        assertEquals(show.getId(), result.get(0).getGroupId());
        assertTrue(result.get(0).getWatched());
        assertNotNull(result.get(0).getLastWatched());
    }

    @Test
    void findRecentEpisodeEntriesIgnoresHistoryOlderThanTheCutoff() {
        UserEntity user = em.persist(UserEntity.builder().externalId("user-2").build());
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.SHOW).name("Shows2").build());
        ShowEntity show = em.persist(ShowEntity.builder().libraryEntity(library).name("Show2").releaseYear(2021).build());
        SeasonEntity season = em.persist(SeasonEntity.builder().showEntity(show).number(1).build());
        EpisodeEntity episode = em.persist(EpisodeEntity.builder().showEntity(show).seasonEntity(season).number(1).build());
        em.persistAndFlush(watchStatus(user, episode, null));

        assertTrue(watchStatusRepository.findRecentEpisodeEntries(user.getId(), Instant.now().plusSeconds(60)).isEmpty());
    }

    @Test
    void findNextUnwatchedEpisodeIdCrossesSeasonsAndSkipsWhatIsWatched() {
        UserEntity user = em.persist(UserEntity.builder().externalId("user-3").build());
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.SHOW).name("Shows3").build());
        ShowEntity show = em.persist(ShowEntity.builder().libraryEntity(library).name("Show3").releaseYear(2020).build());
        SeasonEntity season1 = em.persist(SeasonEntity.builder().showEntity(show).number(1).build());
        SeasonEntity season2 = em.persist(SeasonEntity.builder().showEntity(show).number(2).build());
        EpisodeEntity s1e2 = em.persist(EpisodeEntity.builder().showEntity(show).seasonEntity(season1).number(2).build());
        EpisodeEntity s2e1 = em.persist(EpisodeEntity.builder().showEntity(show).seasonEntity(season2).number(1).build());
        EpisodeEntity s2e2 = em.persist(EpisodeEntity.builder().showEntity(show).seasonEntity(season2).number(2).build());
        // The user finished the first episode of season 2 out of order; it must be skipped.
        em.persistAndFlush(watchStatus(user, s2e1, null));

        assertEquals(List.of(s2e2.getId()),
                episodeRepository.findNextUnwatchedEpisodeId(show.getId(), user.getId(), 1, 2),
                "the next unwatched episode after s1e2 is s2e2, not the finished s2e1");
        assertEquals(List.of(s1e2.getId()),
                episodeRepository.findNextUnwatchedEpisodeId(show.getId(), user.getId(), -1, -1),
                "from before the first episode, the first unwatched one");
        assertTrue(episodeRepository.findNextUnwatchedEpisodeId(show.getId(), user.getId(), 2, 2).isEmpty(),
                "nothing after the last episode");
    }

    @Test
    void continueWatchingUpsertInsertsThenUpdatesAndOnlyMovesLastWatchedForward() {
        UserEntity user = em.persist(UserEntity.builder().externalId("user-4").build());
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.SHOW).name("Shows4").build());
        ShowEntity show = em.persist(ShowEntity.builder().libraryEntity(library).name("Show4").releaseYear(2020).build());
        SeasonEntity season = em.persist(SeasonEntity.builder().showEntity(show).number(1).build());
        EpisodeEntity episode1 = em.persist(EpisodeEntity.builder().showEntity(show).seasonEntity(season).number(1).build());
        EpisodeEntity episode2 = em.persist(EpisodeEntity.builder().showEntity(show).seasonEntity(season).number(2).build());
        em.flush();
        // The column is TIMESTAMP(6); comparing against a nanosecond-precision Instant would read the
        // stored value back as fractionally older than what was written.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        continueWatchingRepository.upsert(user.getId(), MediaType.EPISODE.name(), show.getId(),
                episode1.getId(), null, null, null, null, now);
        continueWatchingRepository.upsert(user.getId(), MediaType.EPISODE.name(), show.getId(),
                episode2.getId(), null, null, null, null, now.minus(Duration.ofDays(1)));
        em.clear();

        List<ContinueWatchingEntity> entries =
                continueWatchingRepository.findEntries(user.getId(), now.minus(Duration.ofDays(150)));

        assertEquals(1, entries.size(), "the second upsert must update the entry, not add one");
        assertEquals(episode2.getId(), entries.get(0).getEpisodeEntity().getId());
        assertFalse(entries.get(0).getLastWatched().isBefore(now), "an out-of-order write cannot pull the entry back");
    }

    @Test
    void findEntriesLeavesOutEntriesWithNothingLeftToResume() {
        UserEntity user = em.persist(UserEntity.builder().externalId("user-5").build());
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.SHOW).name("Shows5").build());
        ShowEntity show = em.persist(ShowEntity.builder().libraryEntity(library).name("Show5").releaseYear(2020).build());
        em.flush();

        continueWatchingRepository.upsert(user.getId(), MediaType.EPISODE.name(), show.getId(),
                null, null, null, null, null, Instant.now());
        em.clear();

        assertTrue(continueWatchingRepository
                .findEntries(user.getId(), Instant.now().minus(Duration.ofDays(150))).isEmpty());
    }

    @Test
    void mediaFileStreamUpsertInsertsThenUpdatesOnConflict() {
        MediaFileEntity mediaFile = persistMediaFile("media/file.mkv");

        mediaFileStreamRepository.upsert(new MediaFileStreamRepository.StreamUpsert(
                "h264", StreamCodecType.VIDEO.name(), 1080, "en",
                mediaFile.getId(), "file.mkv", 0, "title", 1920));
        mediaFileStreamRepository.upsert(new MediaFileStreamRepository.StreamUpsert(
                "hevc", StreamCodecType.VIDEO.name(), 1080, "en",
                mediaFile.getId(), "file.mkv", 0, "title", 1920));

        var streams = mediaFileStreamRepository.findByMediaFileEntity_IdAndCodecType(mediaFile.getId(), StreamCodecType.VIDEO);
        assertEquals(1, streams.size(), "second upsert should update, not insert");
        assertEquals("hevc", streams.get(0).getCodecName());
    }

    @Test
    void deleteAllByMediaFileEntityIdRemovesStreams() {
        MediaFileEntity mediaFile = persistMediaFile("media/other.mkv");
        mediaFileStreamRepository.upsert(new MediaFileStreamRepository.StreamUpsert(
                "aac", StreamCodecType.AUDIO.name(), 0, "nl",
                mediaFile.getId(), "other.mkv", 1, "audio", 0));

        mediaFileStreamRepository.deleteAllByMediaFileEntityId(mediaFile.getId());

        assertEquals(0, mediaFileStreamRepository
                .findByMediaFileEntity_IdAndCodecType(mediaFile.getId(), StreamCodecType.AUDIO).size());
    }

    @Test
    void personWithoutLibraryAndCreditCanBePersisted() {
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.MOVIE).name("Movies-p").build());
        MovieEntity movie = em.persist(MovieEntity.builder().libraryEntity(library).name("Movie-p").releaseYear(2020).build());
        // A TMDB cast person has no music library but does have a tmdbId and birth year.
        PersonEntity person = em.persist(PersonEntity.builder().name("Lady Gaga").tmdbId(90633L).birthYear(1986).build());
        CreditEntity credit = CreditEntity.builder()
                .personEntity(person).characterName("Ally").creditType(CreditType.CAST).castOrder(0).tmdbCreditId("c1").build();
        credit.setMovieEntity(movie);
        em.persistAndFlush(credit);
        em.clear();

        CreditEntity found = em.find(CreditEntity.class, credit.getId());
        assertEquals("Ally", found.getCharacterName());
        assertEquals(CreditType.CAST, found.getCreditType());
        assertEquals(movie.getId(), found.getMovieEntityId());
        assertEquals("Lady Gaga", found.getPersonEntity().getName());
        assertEquals(1986, found.getPersonEntity().getBirthYear());
    }

    @Test
    void creditLibraryQueriesFilterOnMovieShowAndEpisodeLibraries() {
        LibraryEntity movieLibrary = em.persist(LibraryEntity.builder().libraryType(LibraryType.MOVIE).name("Movies-cl").build());
        LibraryEntity showLibrary = em.persist(LibraryEntity.builder().libraryType(LibraryType.SHOW).name("Shows-cl").build());
        MovieEntity movie = em.persist(MovieEntity.builder().libraryEntity(movieLibrary).name("Movie-cl").releaseYear(2020).build());
        ShowEntity show = em.persist(ShowEntity.builder().libraryEntity(showLibrary).name("Show-cl").releaseYear(2021).build());
        SeasonEntity season = em.persist(SeasonEntity.builder().showEntity(show).number(1).build());
        EpisodeEntity episode = em.persist(EpisodeEntity.builder().showEntity(show).seasonEntity(season).number(1).build());
        PersonEntity person = em.persist(PersonEntity.builder().name("Cast Only").tmdbId(4242L).build());

        CreditEntity movieCredit = CreditEntity.builder()
                .personEntity(person).creditType(CreditType.CAST).castOrder(2).tmdbCreditId("cl-m").build();
        movieCredit.setMovieEntity(movie);
        em.persist(movieCredit);
        CreditEntity showCredit = CreditEntity.builder()
                .personEntity(person).creditType(CreditType.CAST).castOrder(0).tmdbCreditId("cl-s").build();
        showCredit.setShowEntity(show);
        em.persist(showCredit);
        CreditEntity episodeCredit = CreditEntity.builder()
                .personEntity(person).creditType(CreditType.GUEST_STAR).castOrder(1).tmdbCreditId("cl-e").build();
        episodeCredit.setEpisodeEntity(episode);
        em.persist(episodeCredit);
        em.flush();

        assertTrue(creditRepository.hasCreditInLibraries(person.getId(), List.of(movieLibrary.getId())));
        assertTrue(creditRepository.hasCreditInLibraries(person.getId(), List.of(showLibrary.getId())));
        assertFalse(creditRepository.hasCreditInLibraries(person.getId(), List.of(UUID.randomUUID())));

        // Movie library only: just the movie credit.
        assertEquals(List.of(movieCredit.getId()),
                creditRepository.findByPersonEntityIdInLibraries(person.getId(), List.of(movieLibrary.getId())).stream()
                        .map(CreditEntity::getId).toList());
        // Show library: show credit and episode credit (episode reaches its library via its show), castOrder ascending.
        assertEquals(List.of(showCredit.getId(), episodeCredit.getId()),
                creditRepository.findByPersonEntityIdInLibraries(person.getId(), List.of(showLibrary.getId())).stream()
                        .map(CreditEntity::getId).toList());
        // Both libraries: all three, castOrder ascending across parents.
        assertEquals(List.of(showCredit.getId(), episodeCredit.getId(), movieCredit.getId()),
                creditRepository.findByPersonEntityIdInLibraries(person.getId(),
                                List.of(movieLibrary.getId(), showLibrary.getId())).stream()
                        .map(CreditEntity::getId).toList());
    }

    // --- play queue chunk queries (seeded shuffle + natural order pagination) ---

    @Test
    void movieLibraryShufflePagesAreDeterministicAndFreeOfDuplicates() {
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.MOVIE).name("Movies-shuffle").build());
        List<UUID> movieIds = IntStream.range(0, 20)
                .mapToObj(i -> em.persist(MovieEntity.builder().libraryEntity(library).name("Movie-" + i).releaseYear(2000 + i).build()).getId())
                .toList();
        em.flush();
        String seed = UUID.randomUUID().toString();
        UUID noExclusion = new UUID(0, 0);

        List<UUID> paged = new ArrayList<>();
        paged.addAll(movieRepository.findMovieIdsForLibraryShuffled(library.getId(), seed, noExclusion, 7, 0));
        paged.addAll(movieRepository.findMovieIdsForLibraryShuffled(library.getId(), seed, noExclusion, 7, 7));
        paged.addAll(movieRepository.findMovieIdsForLibraryShuffled(library.getId(), seed, noExclusion, 7, 14));

        List<UUID> allAtOnce = movieRepository.findMovieIdsForLibraryShuffled(library.getId(), seed, noExclusion, 20, 0);
        assertEquals(allAtOnce, paged, "chunked pages must continue the same permutation");
        assertEquals(20, new HashSet<>(paged).size(), "no duplicates across pages");
        assertEquals(new HashSet<>(movieIds), new HashSet<>(paged), "every movie appears exactly once");

        List<UUID> otherSeed = movieRepository.findMovieIdsForLibraryShuffled(library.getId(), UUID.randomUUID().toString(), noExclusion, 20, 0);
        assertNotEquals(allAtOnce, otherSeed, "a different seed should give a different order");
    }

    @Test
    void movieLibraryShuffleExcludesTheStartItem() {
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.MOVIE).name("Movies-exclude").build());
        List<UUID> movieIds = IntStream.range(0, 5)
                .mapToObj(i -> em.persist(MovieEntity.builder().libraryEntity(library).name("MovieX-" + i).releaseYear(2000 + i).build()).getId())
                .toList();
        em.flush();
        UUID excluded = movieIds.getFirst();

        List<UUID> result = movieRepository.findMovieIdsForLibraryShuffled(library.getId(), "seed", excluded, 10, 0);

        assertEquals(4, result.size());
        assertFalse(result.contains(excluded));
    }

    @Test
    void trackLibraryShufflePagesAreDeterministicAndFreeOfDuplicates() {
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music-shuffle").build());
        PersonEntity artist = em.persist(PersonEntity.builder().name("Artist").build());
        AlbumEntity album = em.persist(AlbumEntity.builder().libraryEntity(library).personEntity(artist).name("Album").releaseYear(2020).build());
        List<UUID> trackIds = IntStream.range(0, 10)
                .mapToObj(i -> em.persist(TrackEntity.builder().albumEntity(album).personEntity(artist).number(i + 1).discNumber(1).build()).getId())
                .toList();
        em.flush();
        String seed = "track-seed";
        UUID noExclusion = new UUID(0, 0);

        List<UUID> paged = new ArrayList<>();
        paged.addAll(trackRepository.findTrackIdsForLibraryShuffled(library.getId(), seed, noExclusion, 4, 0));
        paged.addAll(trackRepository.findTrackIdsForLibraryShuffled(library.getId(), seed, noExclusion, 4, 4));
        paged.addAll(trackRepository.findTrackIdsForLibraryShuffled(library.getId(), seed, noExclusion, 4, 8));

        assertEquals(trackRepository.findTrackIdsForLibraryShuffled(library.getId(), seed, noExclusion, 10, 0), paged);
        assertEquals(new HashSet<>(trackIds), new HashSet<>(paged));
    }

    @Test
    void episodesForShowOrderedFollowsSeasonAndEpisodeNumberAcrossPages() {
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.SHOW).name("Shows-ordered").build());
        ShowEntity show = em.persist(ShowEntity.builder().libraryEntity(library).name("Show-ordered").releaseYear(2020).build());
        SeasonEntity season1 = em.persist(SeasonEntity.builder().showEntity(show).number(1).build());
        SeasonEntity season2 = em.persist(SeasonEntity.builder().showEntity(show).number(2).build());
        List<UUID> expectedOrder = new ArrayList<>();
        // Persist season 2 episodes first to prove ordering comes from the query, not insertion.
        List<EpisodeEntity> season2Episodes = IntStream.range(1, 4)
                .mapToObj(i -> em.persist(EpisodeEntity.builder().showEntity(show).seasonEntity(season2).number(i).build()))
                .toList();
        IntStream.range(1, 4).forEach(i ->
                expectedOrder.add(em.persist(EpisodeEntity.builder().showEntity(show).seasonEntity(season1).number(i).build()).getId()));
        season2Episodes.forEach(e -> expectedOrder.add(e.getId()));
        em.flush();

        List<UUID> paged = new ArrayList<>();
        paged.addAll(episodeRepository.findEpisodeIdsForShowOrdered(show.getId(), 4, 0));
        paged.addAll(episodeRepository.findEpisodeIdsForShowOrdered(show.getId(), 4, 4));

        assertEquals(expectedOrder, paged);
    }

    @Test
    void trackPlayStatsCountOneRowPerPlayedQueueItem() {
        UserEntity user = em.persist(UserEntity.builder().externalId("listener-1").build());
        UserEntity other = em.persist(UserEntity.builder().externalId("listener-2").build());
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music-stats").build());
        PersonEntity artist = em.persist(PersonEntity.builder().name("Artist-stats").build());
        AlbumEntity album = em.persist(AlbumEntity.builder().libraryEntity(library).personEntity(artist).name("Album").releaseYear(2020).build());
        TrackEntity trackA = em.persist(TrackEntity.builder().albumEntity(album).personEntity(artist).number(1).discNumber(1).build());
        TrackEntity trackB = em.persist(TrackEntity.builder().albumEntity(album).personEntity(artist).number(2).discNumber(1).build());
        // Two plays of A (two queue items), one of B, and someone else's play that must not count.
        em.persist(trackPlay(user, trackA));
        em.persist(trackPlay(user, trackA));
        em.persist(trackPlay(user, trackB));
        em.persist(trackPlay(other, trackA));
        em.flush();

        var stats = watchStatusRepository.findTrackPlayStats("listener-1", List.of(trackA.getId(), trackB.getId()));

        assertEquals(2, stats.size());
        var byId = stats.stream().collect(java.util.stream.Collectors.toMap(
                WatchStatusRepository.TrackPlayStats::getTrackId, s -> s));
        assertEquals(2, byId.get(trackA.getId()).getPlays());
        assertEquals(1, byId.get(trackB.getId()).getPlays());
        assertNotNull(byId.get(trackA.getId()).getLastPlayedAt());
    }

    /** Feeds whole-book listening progress: every chapter, its duration, and this user's position. */
    @Test
    void chapterProgressReturnsEveryChapterWithItsDurationAndThisUsersPosition() {
        UserEntity user = em.persist(UserEntity.builder().externalId("listener-book").build());
        UserEntity other = em.persist(UserEntity.builder().externalId("listener-book-2").build());
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.BOOK).name("Books-progress").build());
        PersonEntity author = em.persist(PersonEntity.builder().name("Author-progress").build());
        BookEntity book = em.persist(BookEntity.builder()
                .libraryEntity(library).personEntity(author).name("Book-progress").releaseYear(2021).build());
        ChapterEntity chapter1 = em.persist(ChapterEntity.builder().bookEntity(book).personEntity(author).number(1).build());
        ChapterEntity chapter2 = em.persist(ChapterEntity.builder().bookEntity(book).personEntity(author).number(2).build());
        // Chapter 3 has no media file at all: it must still come back, without a duration.
        em.persist(ChapterEntity.builder().bookEntity(book).personEntity(author).number(3).build());
        MediaFileEntity file1 = persistMediaFile("chapter-1.m4b");
        file1.setChapterEntity(chapter1);
        file1.setDurationInMilliseconds(3_600_000L);
        MediaFileEntity file2 = persistMediaFile("chapter-2.m4b");
        file2.setChapterEntity(chapter2);
        file2.setDurationInMilliseconds(1_800_000L);
        em.persist(WatchStatusEntity.builder().userEntity(user).playQueueItemId(chapter1.getId())
                .chapterEntity(chapter1).watched(true).progressInMilliseconds(3_600_000L).build());
        em.persist(WatchStatusEntity.builder().userEntity(user).playQueueItemId(chapter2.getId())
                .chapterEntity(chapter2).watched(false).progressInMilliseconds(600_000L).build());
        // Someone else's position must not leak into this user's rows.
        em.persist(WatchStatusEntity.builder().userEntity(other).playQueueItemId(chapter2.getId())
                .chapterEntity(chapter2).watched(true).progressInMilliseconds(1_800_000L).build());
        em.flush();

        var rows = watchStatusRepository.findChapterProgress(user.getId(), List.of(book.getId()));

        assertEquals(3, rows.size());
        var byChapterId = rows.stream().collect(java.util.stream.Collectors.toMap(
                WatchStatusRepository.ChapterProgressRow::getChapterId, r -> r));
        assertEquals(book.getId(), byChapterId.get(chapter1.getId()).getBookId());
        assertEquals(3_600_000L, byChapterId.get(chapter1.getId()).getDurationInMilliseconds());
        assertTrue(byChapterId.get(chapter1.getId()).getWatched());
        assertEquals(600_000L, byChapterId.get(chapter2.getId()).getProgressInMilliseconds());
        assertFalse(byChapterId.get(chapter2.getId()).getWatched());
        assertNotNull(byChapterId.get(chapter2.getId()).getUpdatedAt());
        var unanalysed = rows.stream().filter(r -> r.getDurationInMilliseconds() == null).toList();
        assertEquals(1, unanalysed.size());
        assertNull(unanalysed.get(0).getWatched());
    }

    @Test
    void topTrackQueriesRankByPlaysRecencyAndRating() {
        UserEntity user = em.persist(UserEntity.builder().externalId("listener-top").build());
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music-top").build());
        LibraryEntity hidden = em.persist(LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music-hidden").build());
        PersonEntity artist = em.persist(PersonEntity.builder().name("Artist-top").build());
        PersonEntity albumArtist = em.persist(PersonEntity.builder().name("Album-artist").build());
        AlbumEntity album = em.persist(AlbumEntity.builder().libraryEntity(library).personEntity(albumArtist).name("Album").releaseYear(2020).build());
        AlbumEntity hiddenAlbum = em.persist(AlbumEntity.builder().libraryEntity(hidden).personEntity(artist).name("Hidden").releaseYear(2020).build());
        // The album artist differs from the track artist: both must see these tracks.
        TrackEntity trackA = em.persist(TrackEntity.builder().albumEntity(album).personEntity(artist).number(1).discNumber(1).build());
        TrackEntity trackB = em.persist(TrackEntity.builder().albumEntity(album).personEntity(artist).number(2).discNumber(1).build());
        TrackEntity hiddenTrack = em.persist(TrackEntity.builder().albumEntity(hiddenAlbum).personEntity(artist).number(1).discNumber(1).build());
        em.persist(trackPlay(user, trackA));
        em.persistAndFlush(trackPlay(user, trackA));
        // Later plays, but fewer: recency ranks them first, play count ranks them behind A.
        em.persistAndFlush(trackPlay(user, trackB));
        em.persistAndFlush(trackPlay(user, hiddenTrack));
        em.persist(app.ister.core.entity.RatingEntity.builder().userEntity(user).trackEntity(trackA).value(6).build());
        em.persist(app.ister.core.entity.RatingEntity.builder().userEntity(user).trackEntity(trackB).value(9).build());
        em.flush();

        assertEquals(List.of(trackA.getId(), hiddenTrack.getId(), trackB.getId()),
                trackRepository.findTopPlayedTrackIdsForPerson(artist.getId(), "listener-top", Instant.now(), 10, 0));
        assertEquals(List.of(trackA.getId(), trackB.getId()),
                trackRepository.findTopPlayedTrackIdsForPersonInLibraries(artist.getId(), "listener-top", List.of(library.getId()), Instant.now(), 10, 0));
        assertEquals(List.of(hiddenTrack.getId(), trackB.getId(), trackA.getId()),
                trackRepository.findRecentlyPlayedTrackIdsForPerson(artist.getId(), "listener-top", Instant.now(), 10, 0));
        assertEquals(List.of(trackB.getId(), trackA.getId()),
                trackRepository.findTopRatedTrackIdsForPerson(artist.getId(), "listener-top", 10, 0));
        // Album-artist match: the album's artist sees the album's tracks too.
        assertEquals(List.of(trackA.getId(), trackB.getId()),
                trackRepository.findTopPlayedTrackIdsForPersonInLibraries(albumArtist.getId(), "listener-top", List.of(library.getId()), Instant.now(), 10, 0));
        assertEquals(2, trackRepository.findTopPlayedTrackIdsForPerson(artist.getId(), "listener-top", Instant.now(), 2, 0).size(),
                "limit is applied");

        // The ranking is frozen at asOf: plays recorded later don't count, so an ARTIST play
        // queue paging with its creation time keeps a deterministic order while it is played.
        Instant asOf = Instant.now();
        em.persistAndFlush(trackPlay(user, trackB));
        em.persistAndFlush(trackPlay(user, trackB));
        assertEquals(List.of(trackB.getId(), trackA.getId(), hiddenTrack.getId()),
                trackRepository.findTopPlayedTrackIdsForPerson(artist.getId(), "listener-top", Instant.now(), 10, 0),
                "a fresh asOf sees the new plays");
        assertEquals(List.of(trackA.getId(), hiddenTrack.getId(), trackB.getId()),
                trackRepository.findTopPlayedTrackIdsForPerson(artist.getId(), "listener-top", asOf, 10, 0),
                "the frozen asOf does not");
        assertEquals(List.of(hiddenTrack.getId(), trackB.getId()),
                trackRepository.findTopPlayedTrackIdsForPerson(artist.getId(), "listener-top", asOf, 10, 1),
                "offset pages the frozen ranking");
    }

    // --- library-wide browse (tracks/episodes) ---

    /**
     * The library-wide track browse sorts on metadata columns via a left join with MIN aggregates:
     * a track with several metadata rows must appear once (under its alphabetically first title),
     * tracks without metadata must sort last in both directions, and other libraries stay out.
     */
    @Test
    void trackBrowseSortsOnMetadataWithoutDuplicatingMultiMetadataTracks() {
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music-browse").build());
        LibraryEntity otherLibrary = em.persist(LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music-browse-other").build());
        PersonEntity artist = em.persist(PersonEntity.builder().name("Artist-browse").build());
        AlbumEntity album = em.persist(AlbumEntity.builder().libraryEntity(library).personEntity(artist).name("Album").releaseYear(2020).build());
        AlbumEntity otherAlbum = em.persist(AlbumEntity.builder().libraryEntity(otherLibrary).personEntity(artist).name("Other").releaseYear(2020).build());
        TrackEntity apple = em.persist(TrackEntity.builder().albumEntity(album).personEntity(artist).number(1).discNumber(1).build());
        TrackEntity banana = em.persist(TrackEntity.builder().albumEntity(album).personEntity(artist).number(2).discNumber(1).build());
        TrackEntity untitled = em.persist(TrackEntity.builder().albumEntity(album).personEntity(artist).number(3).discNumber(1).build());
        TrackEntity elsewhere = em.persist(TrackEntity.builder().albumEntity(otherAlbum).personEntity(artist).number(1).discNumber(1).build());
        em.persist(MetadataEntity.builder().trackEntity(apple).title("Apple").released(LocalDate.of(2021, 1, 1)).build());
        em.persist(MetadataEntity.builder().trackEntity(banana).title("Banana").released(LocalDate.of(2019, 1, 1)).build());
        em.persist(MetadataEntity.builder().trackEntity(banana).title("Cherry").released(LocalDate.of(2022, 1, 1)).build());
        em.persist(MetadataEntity.builder().trackEntity(elsewhere).title("Aardvark").build());
        em.flush();

        Page<TrackEntity> byNameAsc = trackRepository.findInLibraries(List.of(library.getId()),
                SortingEnum.NAME, SortingOrder.ASCENDING, PageRequest.of(0, 10));
        assertEquals(3, byNameAsc.getTotalElements(), "multi-metadata tracks count once, other libraries not at all");
        assertEquals(List.of(apple.getId(), banana.getId(), untitled.getId()), ids(byNameAsc));

        assertEquals(List.of(banana.getId(), apple.getId(), untitled.getId()),
                ids(trackRepository.findInLibraries(List.of(library.getId()),
                        SortingEnum.NAME, SortingOrder.DESCENDING, PageRequest.of(0, 10))),
                "untitled tracks sort last in both directions");

        assertEquals(List.of(banana.getId(), apple.getId(), untitled.getId()),
                ids(trackRepository.findInLibraries(List.of(library.getId()),
                        SortingEnum.RELEASE_YEAR, SortingOrder.ASCENDING, PageRequest.of(0, 10))),
                "release sorts on the earliest metadata date");

        Page<TrackEntity> firstPage = trackRepository.findInLibraries(List.of(library.getId()),
                SortingEnum.NAME, SortingOrder.ASCENDING, PageRequest.of(0, 2));
        assertEquals(List.of(apple.getId(), banana.getId()), ids(firstPage));
        assertEquals(3, firstPage.getTotalElements(), "the count query totals the same filter");
        assertEquals(List.of(untitled.getId()),
                ids(trackRepository.findInLibraries(List.of(library.getId()),
                        SortingEnum.NAME, SortingOrder.ASCENDING, PageRequest.of(1, 2))));

        Page<TrackEntity> newestAdded = trackRepository.findInLibraries(List.of(library.getId()),
                SortingEnum.DATE_CREATED, SortingOrder.DESCENDING, PageRequest.of(0, 10));
        assertEquals(3, newestAdded.getTotalElements());
    }

    /** Same shape for episodes: metadata title/air date across every show of the library. */
    @Test
    void episodeBrowseSortsOnMetadataAcrossShows() {
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.SHOW).name("Shows-browse").build());
        ShowEntity show1 = em.persist(ShowEntity.builder().libraryEntity(library).name("Show-b1").releaseYear(2020).build());
        ShowEntity show2 = em.persist(ShowEntity.builder().libraryEntity(library).name("Show-b2").releaseYear(2021).build());
        SeasonEntity season1 = em.persist(SeasonEntity.builder().showEntity(show1).number(1).build());
        SeasonEntity season2 = em.persist(SeasonEntity.builder().showEntity(show2).number(1).build());
        EpisodeEntity pilot = em.persist(EpisodeEntity.builder().showEntity(show1).seasonEntity(season1).number(1).build());
        EpisodeEntity finale = em.persist(EpisodeEntity.builder().showEntity(show2).seasonEntity(season2).number(1).build());
        EpisodeEntity unnamed = em.persist(EpisodeEntity.builder().showEntity(show1).seasonEntity(season1).number(2).build());
        em.persist(MetadataEntity.builder().episodeEntity(pilot).title("Pilot").released(LocalDate.of(2020, 1, 1)).build());
        em.persist(MetadataEntity.builder().episodeEntity(finale).title("Finale").released(LocalDate.of(2020, 6, 1)).build());
        em.flush();

        assertEquals(List.of(finale.getId(), pilot.getId(), unnamed.getId()),
                ids(episodeRepository.findInLibraries(List.of(library.getId()),
                        SortingEnum.NAME, SortingOrder.ASCENDING, PageRequest.of(0, 10))),
                "episodes sort across shows on the metadata title");

        assertEquals(List.of(finale.getId(), pilot.getId(), unnamed.getId()),
                ids(episodeRepository.findInLibraries(List.of(library.getId()),
                        SortingEnum.RELEASE_YEAR, SortingOrder.DESCENDING, PageRequest.of(0, 10))),
                "air-date sort, undated episodes last");

        assertEquals(3, episodeRepository.findInLibraries(List.of(library.getId()),
                SortingEnum.DATE_CREATED, SortingOrder.DESCENDING, PageRequest.of(0, 10)).getTotalElements());
    }

    private static List<UUID> ids(Page<? extends app.ister.core.entity.BaseEntity> page) {
        return page.getContent().stream().map(app.ister.core.entity.BaseEntity::getId).toList();
    }

    // --- library Discover top-lists ---

    /**
     * Movie plays only count once watched or past two minutes: a movie's watch row is created
     * the moment playback starts, and an abandoned start must not surface in Discover.
     */
    @Test
    void discoverMovieQueriesApplyThePlayedThresholdAndRankByPlaysRecencyAndRating() {
        UserEntity user = em.persist(UserEntity.builder().externalId("viewer-d1").build());
        UserEntity other = em.persist(UserEntity.builder().externalId("viewer-d2").build());
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.MOVIE).name("Movies-d").build());
        LibraryEntity otherLibrary = em.persist(LibraryEntity.builder().libraryType(LibraryType.MOVIE).name("Movies-d2").build());
        MovieEntity twicePlayed = em.persist(MovieEntity.builder().libraryEntity(library).name("Twice").releaseYear(2020).build());
        MovieEntity oncePlayed = em.persist(MovieEntity.builder().libraryEntity(library).name("Once").releaseYear(2021).build());
        MovieEntity abandoned = em.persist(MovieEntity.builder().libraryEntity(library).name("Abandoned").releaseYear(2022).build());
        MovieEntity elsewhere = em.persist(MovieEntity.builder().libraryEntity(otherLibrary).name("Elsewhere").releaseYear(2023).build());
        em.persist(moviePlay(user, twicePlayed, true, 0));
        em.persistAndFlush(moviePlay(user, twicePlayed, true, 0));
        // Later play, but fewer: recency ranks it first, play count ranks it behind.
        em.persistAndFlush(moviePlay(user, oncePlayed, false, 600_000));
        em.persist(moviePlay(user, abandoned, false, 30_000));
        em.persist(moviePlay(user, elsewhere, true, 0));
        em.persist(moviePlay(other, abandoned, true, 0));
        em.persist(app.ister.core.entity.RatingEntity.builder().userEntity(user).movieEntity(oncePlayed).value(7).build());
        em.persist(app.ister.core.entity.RatingEntity.builder().userEntity(user).movieEntity(abandoned).value(9).build());
        em.flush();

        assertEquals(List.of(oncePlayed.getId(), twicePlayed.getId()),
                movieRepository.findRecentlyPlayedMovieIdsForLibrary(library.getId(), "viewer-d1", 10, 0),
                "abandoned start and other library/user must not appear");
        assertEquals(List.of(twicePlayed.getId(), oncePlayed.getId()),
                movieRepository.findMostPlayedMovieIdsForLibrary(library.getId(), "viewer-d1", 10, 0));
        assertEquals(List.of(abandoned.getId(), oncePlayed.getId()),
                movieRepository.findHighestRatedMovieIdsForLibrary(library.getId(), "viewer-d1", 10, 0),
                "ratings rank regardless of plays");
        assertEquals(1, movieRepository.findMostPlayedMovieIdsForLibrary(library.getId(), "viewer-d1", 1, 0).size(),
                "limit is applied");
    }

    /** The "show all" grids page the ranked lists: offset slices, count totals the same filter. */
    @Test
    void discoverMovieQueriesPageWithOffsetAndCountTheirTotals() {
        UserEntity user = em.persist(UserEntity.builder().externalId("viewer-d4").build());
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.MOVIE).name("Movies-d4").build());
        MovieEntity newest = em.persist(MovieEntity.builder().libraryEntity(library).name("Newest").releaseYear(2020).build());
        MovieEntity middle = em.persist(MovieEntity.builder().libraryEntity(library).name("Middle").releaseYear(2021).build());
        MovieEntity oldest = em.persist(MovieEntity.builder().libraryEntity(library).name("Oldest").releaseYear(2022).build());
        MovieEntity abandoned = em.persist(MovieEntity.builder().libraryEntity(library).name("Abandoned").releaseYear(2023).build());
        em.persistAndFlush(moviePlay(user, oldest, true, 0));
        em.persistAndFlush(moviePlay(user, middle, true, 0));
        em.persistAndFlush(moviePlay(user, newest, true, 0));
        em.persist(moviePlay(user, abandoned, false, 30_000));
        em.persist(app.ister.core.entity.RatingEntity.builder().userEntity(user).movieEntity(newest).value(9).build());
        em.persist(app.ister.core.entity.RatingEntity.builder().userEntity(user).movieEntity(oldest).value(7).build());
        em.flush();

        assertEquals(List.of(newest.getId(), middle.getId()),
                movieRepository.findRecentlyPlayedMovieIdsForLibrary(library.getId(), "viewer-d4", 2, 0));
        assertEquals(List.of(oldest.getId()),
                movieRepository.findRecentlyPlayedMovieIdsForLibrary(library.getId(), "viewer-d4", 2, 2),
                "the second page continues where the first ended");
        assertEquals(3, movieRepository.countPlayedMoviesForLibrary(library.getId(), "viewer-d4"),
                "the abandoned start counts for neither the pages nor the total");
        assertEquals(List.of(oldest.getId()),
                movieRepository.findHighestRatedMovieIdsForLibrary(library.getId(), "viewer-d4", 5, 1));
        assertEquals(2, movieRepository.countRatedMoviesForLibrary(library.getId(), "viewer-d4"));
    }

    /** Show lists aggregate episode plays; album lists aggregate track plays. */
    @Test
    void discoverShowAndAlbumQueriesAggregateOverEpisodesAndTracks() {
        UserEntity user = em.persist(UserEntity.builder().externalId("viewer-d3").build());
        LibraryEntity showLibrary = em.persist(LibraryEntity.builder().libraryType(LibraryType.SHOW).name("Shows-d").build());
        ShowEntity bingeShow = em.persist(ShowEntity.builder().libraryEntity(showLibrary).name("Binge").releaseYear(2020).build());
        ShowEntity casualShow = em.persist(ShowEntity.builder().libraryEntity(showLibrary).name("Casual").releaseYear(2021).build());
        SeasonEntity bingeSeason = em.persist(SeasonEntity.builder().showEntity(bingeShow).number(1).build());
        SeasonEntity casualSeason = em.persist(SeasonEntity.builder().showEntity(casualShow).number(1).build());
        EpisodeEntity binge1 = em.persist(EpisodeEntity.builder().showEntity(bingeShow).seasonEntity(bingeSeason).number(1).build());
        EpisodeEntity binge2 = em.persist(EpisodeEntity.builder().showEntity(bingeShow).seasonEntity(bingeSeason).number(2).build());
        EpisodeEntity casual1 = em.persist(EpisodeEntity.builder().showEntity(casualShow).seasonEntity(casualSeason).number(1).build());
        em.persist(watchStatus(user, binge1, null));
        em.persistAndFlush(watchStatus(user, binge2, null));
        em.persistAndFlush(watchStatus(user, casual1, null));
        em.persist(app.ister.core.entity.RatingEntity.builder().userEntity(user).showEntity(casualShow).value(8).build());

        LibraryEntity musicLibrary = em.persist(LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music-d").build());
        PersonEntity artist = em.persist(PersonEntity.builder().name("Artist-d").build());
        AlbumEntity heavyAlbum = em.persist(AlbumEntity.builder().libraryEntity(musicLibrary).personEntity(artist).name("Heavy").releaseYear(2020).build());
        AlbumEntity lightAlbum = em.persist(AlbumEntity.builder().libraryEntity(musicLibrary).personEntity(artist).name("Light").releaseYear(2021).build());
        TrackEntity heavyTrack = em.persist(TrackEntity.builder().albumEntity(heavyAlbum).personEntity(artist).number(1).discNumber(1).build());
        TrackEntity lightTrack = em.persist(TrackEntity.builder().albumEntity(lightAlbum).personEntity(artist).number(1).discNumber(1).build());
        em.persist(trackPlay(user, heavyTrack));
        em.persistAndFlush(trackPlay(user, heavyTrack));
        em.persistAndFlush(trackPlay(user, lightTrack));
        em.flush();

        assertEquals(List.of(casualShow.getId(), bingeShow.getId()),
                showRepository.findRecentlyPlayedShowIdsForLibrary(showLibrary.getId(), "viewer-d3", 10, 0));
        assertEquals(List.of(bingeShow.getId(), casualShow.getId()),
                showRepository.findMostPlayedShowIdsForLibrary(showLibrary.getId(), "viewer-d3", 10, 0),
                "two episode plays outrank one");
        assertEquals(List.of(casualShow.getId()),
                showRepository.findHighestRatedShowIdsForLibrary(showLibrary.getId(), "viewer-d3", 10, 0));
        assertEquals(List.of(lightAlbum.getId(), heavyAlbum.getId()),
                albumRepository.findRecentlyPlayedAlbumIdsForLibrary(musicLibrary.getId(), "viewer-d3", 10, 0));
        assertEquals(List.of(heavyAlbum.getId(), lightAlbum.getId()),
                albumRepository.findMostPlayedAlbumIdsForLibrary(musicLibrary.getId(), "viewer-d3", 10, 0));
    }

    /**
     * A book's reading progress lives on its own watch row (epub) and on its chapters' rows
     * (audiobook); both must surface the book. Comic series aggregate their volumes' reading rows.
     */
    @Test
    void discoverBookAndSeriesQueriesCoverReadingAndListeningRows() {
        UserEntity user = em.persist(UserEntity.builder().externalId("reader-d1").build());
        LibraryEntity bookLibrary = em.persist(LibraryEntity.builder().libraryType(LibraryType.BOOK).name("Books-d").build());
        PersonEntity author = em.persist(PersonEntity.builder().name("Author-d").build());
        app.ister.core.entity.BookEntity epub = em.persist(app.ister.core.entity.BookEntity.builder()
                .libraryEntity(bookLibrary).personEntity(author).name("Epub").build());
        app.ister.core.entity.BookEntity audiobook = em.persist(app.ister.core.entity.BookEntity.builder()
                .libraryEntity(bookLibrary).personEntity(author).name("Audio").build());
        app.ister.core.entity.ChapterEntity chapter = em.persist(app.ister.core.entity.ChapterEntity.builder()
                .personEntity(author).bookEntity(audiobook).number(1).build());
        em.persistAndFlush(WatchStatusEntity.builder()
                .playQueueItemId(epub.getId()).userEntity(user).bookEntity(epub)
                .readingProgress(0.4).build());
        em.persistAndFlush(WatchStatusEntity.builder()
                .playQueueItemId(chapter.getId()).userEntity(user).chapterEntity(chapter)
                .progressInMilliseconds(90_000).build());

        LibraryEntity comicLibrary = em.persist(LibraryEntity.builder().libraryType(LibraryType.COMIC).name("Comics-d").build());
        SeriesEntity series = em.persist(SeriesEntity.builder().libraryEntity(comicLibrary).name("Series-d").startYear(2009).build());
        app.ister.core.entity.BookEntity volume = em.persist(app.ister.core.entity.BookEntity.builder()
                .libraryEntity(comicLibrary).seriesEntity(series).name("Vol 1").build());
        em.persistAndFlush(WatchStatusEntity.builder()
                .playQueueItemId(volume.getId()).userEntity(user).bookEntity(volume)
                .readingProgress(0.2).build());
        em.flush();

        assertEquals(List.of(audiobook.getId(), epub.getId()),
                bookRepository.findRecentlyReadBookIdsForLibrary(bookLibrary.getId(), "reader-d1", 10, 0),
                "the chapter listen is newer than the epub read");
        assertEquals(List.of(series.getId()),
                seriesRepository.findRecentlyReadSeriesIdsForLibrary(comicLibrary.getId(), "reader-d1", 10, 0));
    }

    /** Podcast lists aggregate episode plays with the same threshold, and skip inactive feeds. */
    @Test
    void discoverPodcastQueriesSkipInactiveFeedsAndAbandonedStarts() {
        UserEntity user = em.persist(UserEntity.builder().externalId("listener-d1").build());
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.PODCAST).name("Podcasts-d").build());
        app.ister.core.entity.PodcastEntity active = em.persist(app.ister.core.entity.PodcastEntity.builder()
                .libraryEntity(library).feedUrl("https://feed/active").title("Active").active(true).build());
        app.ister.core.entity.PodcastEntity unsubscribed = em.persist(app.ister.core.entity.PodcastEntity.builder()
                .libraryEntity(library).feedUrl("https://feed/old").title("Old").active(false).build());
        app.ister.core.entity.PodcastEpisodeEntity activeEpisode = em.persist(app.ister.core.entity.PodcastEpisodeEntity.builder()
                .podcastEntity(active).guid("g1").enclosureUrl("https://feed/e1.mp3").build());
        app.ister.core.entity.PodcastEpisodeEntity abandonedEpisode = em.persist(app.ister.core.entity.PodcastEpisodeEntity.builder()
                .podcastEntity(active).guid("g2").enclosureUrl("https://feed/e2.mp3").build());
        app.ister.core.entity.PodcastEpisodeEntity unsubscribedEpisode = em.persist(app.ister.core.entity.PodcastEpisodeEntity.builder()
                .podcastEntity(unsubscribed).guid("g3").enclosureUrl("https://feed/e3.mp3").build());
        em.persist(WatchStatusEntity.builder().playQueueItemId(UUID.randomUUID()).userEntity(user)
                .podcastEpisodeEntity(activeEpisode).progressInMilliseconds(300_000).build());
        em.persist(WatchStatusEntity.builder().playQueueItemId(UUID.randomUUID()).userEntity(user)
                .podcastEpisodeEntity(abandonedEpisode).progressInMilliseconds(10_000).build());
        em.persist(WatchStatusEntity.builder().playQueueItemId(UUID.randomUUID()).userEntity(user)
                .podcastEpisodeEntity(unsubscribedEpisode).watched(true).build());
        em.persist(app.ister.core.entity.RatingEntity.builder().userEntity(user).podcastEntity(active).value(8).build());
        em.flush();

        assertEquals(List.of(active.getId()),
                podcastRepository.findRecentlyPlayedPodcastIdsForLibrary(library.getId(), "listener-d1", 10, 0));
        assertEquals(List.of(active.getId()),
                podcastRepository.findMostPlayedPodcastIdsForLibrary(library.getId(), "listener-d1", 10, 0));
        assertEquals(List.of(active.getId()),
                podcastRepository.findHighestRatedPodcastIdsForLibrary(library.getId(), "listener-d1", 10, 0));
    }

    private static WatchStatusEntity moviePlay(UserEntity user, MovieEntity movie, boolean watched, long progressMs) {
        return WatchStatusEntity.builder()
                .playQueueItemId(UUID.randomUUID())
                .userEntity(user)
                .movieEntity(movie)
                .watched(watched)
                .progressInMilliseconds(progressMs)
                .build();
    }

    private static WatchStatusEntity trackPlay(UserEntity user, TrackEntity track) {
        return WatchStatusEntity.builder()
                .playQueueItemId(UUID.randomUUID())
                .userEntity(user)
                .trackEntity(track)
                .watched(true)
                .build();
    }

    @Test
    void ratingCanBeStoredAndReadBackPerUserAndItem() {
        UserEntity user = em.persist(UserEntity.builder().externalId("rater-1").build());
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.MOVIE).name("Movies-rating").build());
        MovieEntity movie = em.persist(MovieEntity.builder().libraryEntity(library).name("Rated").releaseYear(2020).build());
        em.persistAndFlush(app.ister.core.entity.RatingEntity.builder().userEntity(user).movieEntity(movie).value(8).build());
        em.clear();

        assertEquals(8, ratingRepository.findByUserEntityAndMovieEntity(user, movie).orElseThrow().getValue());
        var batch = ratingRepository.findByUserEntityExternalIdAndMovieEntityIn("rater-1", List.of(movie));
        assertEquals(1, batch.size());
        assertEquals(8, batch.get(0).getValue());
    }

    /**
     * The movie heartbeat lookup must find the row written by the previous heartbeat, and it
     * must be unambiguous: the movie-scoped query matches on the movie itself, instead of
     * relying on the episode-scoped query treating its null episode as IS NULL.
     */
    @Test
    void movieWatchStatusLookupFindsTheExistingRow() {
        UserEntity user = em.persist(UserEntity.builder().externalId("movie-watcher-1").build());
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.MOVIE).name("Movies-ws").build());
        MovieEntity movie = em.persist(MovieEntity.builder().libraryEntity(library).name("Movie-ws").releaseYear(2020).build());
        WatchStatusEntity existing = em.persistAndFlush(watchStatus(user, null, movie));
        em.clear();

        var found = watchStatusRepository.findByUserEntityAndPlayQueueItemIdAndMovieEntity(
                user, existing.getPlayQueueItemId(), movie);

        assertEquals(existing.getId(), found.orElseThrow().getId());
    }

    /**
     * The season claim is a native PostgreSQL call with a UUID bound into hashtext, which only a
     * real database can prove: a mock would happily accept SQL Hibernate cannot bind.
     */
    @Test
    void aSeasonCanBeClaimedForSegmentDetection() {
        assertTrue(mediaFileSegmentRepository.tryLockSeason(
                MediaFileSegmentRepository.SEGMENT_DETECTION_LOCK_NAMESPACE, UUID.randomUUID()));
    }

    /**
     * V39: two detectors racing on one season used to store the same segment once per consumer.
     * The index has to catch that even for single-episode files, where the disambiguating column
     * is null on both rows — hence NULLS NOT DISTINCT.
     */
    @Test
    void theSameSegmentCannotBeStoredTwiceForAFile() {
        MediaFileEntity mediaFile = persistMediaFile("segment-dupes.mkv");
        mediaFileSegmentRepository.save(segment(mediaFile, 0, 30_000));
        em.flush();

        mediaFileSegmentRepository.save(segment(mediaFile, 0, 30_000));

        assertThrows(PersistenceException.class, em::flush);
    }

    /**
     * Re-detection replaces a file's segments with delete-then-insert in one transaction.
     * Hibernate flushes inserts before entity deletes, so with a derived delete the new row
     * used to collide with the old one on the (file, type, episode) unique index — the delete
     * has to be a bulk statement that runs immediately. Only a real database with the index
     * in place can prove the ordering.
     */
    @Test
    void reDetectionCanReplaceAnExistingSegment() {
        MediaFileEntity mediaFile = persistMediaFile("segment-replace.mkv");
        mediaFileSegmentRepository.save(segment(mediaFile, 0, 30_000));
        em.flush();

        mediaFileSegmentRepository.deleteAllByMediaFileEntityId(mediaFile.getId());
        mediaFileSegmentRepository.save(segment(mediaFile, 500, 31_000));
        em.flush();

        List<MediaFileSegmentEntity> segments = mediaFileSegmentRepository.findByMediaFileEntityId(mediaFile.getId());
        assertEquals(1, segments.size());
        assertEquals(500, segments.getFirst().getStartInMilliseconds());
    }

    private static MediaFileSegmentEntity segment(MediaFileEntity mediaFile, long startMs, long endMs) {
        return MediaFileSegmentEntity.builder()
                .mediaFileEntityId(mediaFile.getId())
                .type(SegmentType.INTRO)
                .startInMilliseconds(startMs)
                .endInMilliseconds(endMs)
                .build();
    }

    private MediaFileEntity persistMediaFile(String path) {
        NodeEntity node = em.persist(NodeEntity.builder().name("node-" + path).url("http://localhost").build());
        DirectoryEntity directory = em.persist(DirectoryEntity.builder()
                .nodeEntity(node).name("dir-" + path).path("/data/" + path).directoryType(DirectoryType.LIBRARY).build());
        MediaFileEntity mediaFile = MediaFileEntity.builder().size(1).path(path).build();
        mediaFile.setDirectoryEntity(directory);
        return em.persistAndFlush(mediaFile);
    }

    private static WatchStatusEntity watchStatus(UserEntity user, EpisodeEntity episode, MovieEntity movie) {
        return WatchStatusEntity.builder()
                .playQueueItemId(UUID.randomUUID())
                .userEntity(user)
                .episodeEntity(episode)
                .movieEntity(movie)
                .watched(true)
                .build();
    }
}
