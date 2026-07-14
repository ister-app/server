package app.ister.core.repository;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.ContinueWatchingEntity;
import app.ister.core.entity.CreditEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.CreditType;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.StreamCodecType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
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
    private MovieRepository movieRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private ImageRepository imageRepository;

    @Autowired
    private RatingRepository ratingRepository;

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
