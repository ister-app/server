package app.ister.core.service;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.PlaylistEntity;
import app.ister.core.entity.PlaylistItemEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlaylistType;
import app.ister.core.repository.PlaylistItemRepository;
import app.ister.core.repository.PlaylistRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the playlist schema (V33) and the native playlist-item queries against a real PostgreSQL:
 * ddl-auto=validate proves the entities match the migration, and the COALESCE/seeded-shuffle SQL
 * is exactly what mocks can never validate. Skipped when no container runtime is available.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@org.springframework.boot.autoconfigure.ImportAutoConfiguration(org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.context.annotation.Import(app.ister.core.config.PersistenceConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class PlaylistPersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private TestEntityManager em;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private PlaylistItemRepository playlistItemRepository;

    private record Fixture(PlaylistEntity playlist, List<UUID> trackIds) {
    }

    /** A manual music playlist with [count] tracks, positions in insertion order. */
    private Fixture manualMusicPlaylist(String suffix, int count) {
        UserEntity user = em.persist(UserEntity.builder().externalId("playlist-owner-" + suffix).build());
        LibraryEntity library = em.persist(LibraryEntity.builder()
                .libraryType(LibraryType.MUSIC).name("Music-pl-" + suffix).build());
        PersonEntity artist = em.persist(PersonEntity.builder().name("Playlist Artist " + suffix).build());
        AlbumEntity album = em.persist(AlbumEntity.builder()
                .libraryEntity(library).personEntity(artist).name("Album " + suffix).build());
        PlaylistEntity playlist = em.persist(PlaylistEntity.builder()
                .userEntity(user).libraryEntity(library).name("Mix " + suffix).type(PlaylistType.MANUAL).build());
        List<UUID> trackIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            TrackEntity track = em.persist(TrackEntity.builder()
                    .albumEntity(album).personEntity(artist).number(i + 1).discNumber(1).build());
            trackIds.add(track.getId());
            PlaylistItemEntity item = PlaylistItemEntity.builder()
                    .playlistEntity(playlist)
                    .type(MediaType.TRACK)
                    .position(new BigDecimal(1000L * (i + 1)))
                    .build();
            item.setTrackEntityId(track.getId());
            em.persist(item);
        }
        em.flush();
        return new Fixture(playlist, trackIds);
    }

    @Test
    void orderedQueryFollowsPositionsAndPages() {
        Fixture f = manualMusicPlaylist("a", 5);

        assertEquals(f.trackIds(),
                playlistItemRepository.findMediaIdsForPlaylistOrdered(f.playlist().getId(), 10, 0));
        assertEquals(f.trackIds().subList(2, 4),
                playlistItemRepository.findMediaIdsForPlaylistOrdered(f.playlist().getId(), 2, 2));
        assertEquals(5, playlistItemRepository.countByPlaylistEntityId(f.playlist().getId()));
    }

    @Test
    void shuffledQueryIsDeterministicPerSeedAndExcludesTheStartItem() {
        Fixture f = manualMusicPlaylist("b", 10);
        UUID startId = f.trackIds().getFirst();

        List<UUID> first = playlistItemRepository.findMediaIdsForPlaylistShuffled(
                f.playlist().getId(), "seed-1", startId, 20, 0);
        List<UUID> again = playlistItemRepository.findMediaIdsForPlaylistShuffled(
                f.playlist().getId(), "seed-1", startId, 20, 0);
        List<UUID> other = playlistItemRepository.findMediaIdsForPlaylistShuffled(
                f.playlist().getId(), "seed-2", startId, 20, 0);

        assertEquals(first, again, "the same seed yields the same permutation");
        assertEquals(9, first.size(), "the start item is excluded");
        assertEquals(new HashSet<>(f.trackIds().subList(1, 10)), new HashSet<>(first));
        assertNotEquals(first, other, "another seed yields another permutation");
    }

    @Test
    void mixedMediaColumnsResolveThroughCoalesce() {
        UserEntity user = em.persist(UserEntity.builder().externalId("playlist-owner-c").build());
        LibraryEntity library = em.persist(LibraryEntity.builder()
                .libraryType(LibraryType.BOOK).name("Books-pl-c").build());
        BookEntity book = em.persist(BookEntity.builder().libraryEntity(library).name("A Book").build());
        PlaylistEntity playlist = em.persist(PlaylistEntity.builder()
                .userEntity(user).libraryEntity(library).name("Reading list").type(PlaylistType.MANUAL).build());
        PlaylistItemEntity item = PlaylistItemEntity.builder()
                .playlistEntity(playlist)
                .type(MediaType.BOOK)
                .position(new BigDecimal(1000))
                .build();
        item.setBookEntityId(book.getId());
        em.persist(item);
        em.flush();

        assertEquals(List.of(book.getId()),
                playlistItemRepository.findMediaIdsForPlaylistOrdered(playlist.getId(), 10, 0));
    }

    @Test
    void deletingAPlaylistCascadesToItsItems() {
        Fixture f = manualMusicPlaylist("d", 3);
        UUID playlistId = f.playlist().getId();
        em.clear();

        playlistRepository.delete(playlistRepository.findById(playlistId).orElseThrow());
        em.flush();
        em.clear();

        assertEquals(0, playlistItemRepository.countByPlaylistEntityId(playlistId));
        assertTrue(playlistRepository.findById(playlistId).isEmpty());
    }
}
