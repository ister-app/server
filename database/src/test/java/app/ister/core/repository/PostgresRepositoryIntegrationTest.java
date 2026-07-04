package app.ister.core.repository;

import app.ister.core.entity.CreditEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.CreditType;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.StreamCodecType;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    private MediaFileStreamRepository mediaFileStreamRepository;

    @Test
    void flywayMigrationsMatchEntityMappings() {
        // Context startup already ran Flyway V1..Vn and validated the JPA mappings
        // against the migrated schema (ddl-auto=validate). Querying a migrated table
        // proves the schema is reachable and the mapping resolves against it.
        assertEquals(0, mediaFileStreamRepository.count());
    }

    @Test
    void findRecentEpisodesAndShowIdsByUserIdReturnsLatestEpisodePerShow() {
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

        List<String[]> result = watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId());

        assertEquals(1, result.size());
        assertEquals(show.getId().toString(), result.get(0)[1]);
    }

    @Test
    void findRecentEpisodesWithDateByUserIdIncludesDateColumn() {
        UserEntity user = em.persist(UserEntity.builder().externalId("user-2").build());
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.SHOW).name("Shows2").build());
        ShowEntity show = em.persist(ShowEntity.builder().libraryEntity(library).name("Show2").releaseYear(2021).build());
        SeasonEntity season = em.persist(SeasonEntity.builder().showEntity(show).number(1).build());
        EpisodeEntity episode = em.persist(EpisodeEntity.builder().showEntity(show).seasonEntity(season).number(1).build());
        em.persistAndFlush(watchStatus(user, episode, null));

        List<Object[]> result = watchStatusRepository.findRecentEpisodesWithDateByUserId(user.getId());

        assertEquals(1, result.size());
        assertEquals(episode.getId().toString(), String.valueOf(result.get(0)[0]));
        assertNotNull(result.get(0)[2], "date_updated column should be returned");
    }

    @Test
    void findRecentMovieIdsByUserIdReturnsWatchedMovie() {
        UserEntity user = em.persist(UserEntity.builder().externalId("user-3").build());
        LibraryEntity library = em.persist(LibraryEntity.builder().libraryType(LibraryType.MOVIE).name("Movies").build());
        MovieEntity movie = em.persist(MovieEntity.builder().libraryEntity(library).name("Movie").releaseYear(2020).build());
        em.persistAndFlush(watchStatus(user, null, movie));

        List<String> result = watchStatusRepository.findRecentMovieIdsByUserId(user.getId());

        assertEquals(List.of(movie.getId().toString()), result);
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
