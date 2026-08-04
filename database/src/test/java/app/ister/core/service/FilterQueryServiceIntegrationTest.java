package app.ister.core.service;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.RatingEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.filter.FilterCondition;
import app.ister.core.filter.FilterField;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.FilterMatch;
import app.ister.core.filter.FilterOperator;
import app.ister.core.filter.MediaFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the filter-to-SQL translation against a real PostgreSQL: the generated native SQL
 * (EXISTS subqueries over metadata/rating/watch-status, seeded shuffle, limit capping) is
 * exactly what mocks can never validate. Skipped when no container runtime is available.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@org.springframework.boot.autoconfigure.ImportAutoConfiguration(org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.context.annotation.Import({app.ister.core.config.PersistenceConfig.class, FilterQueryService.class})
@Testcontainers(disabledWithoutDocker = true)
class FilterQueryServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static final String LISTENER = "filter-listener";

    @Autowired
    private TestEntityManager em;

    @Autowired
    private FilterQueryService subject;

    private static MediaFilter all(FilterCondition... conditions) {
        return new MediaFilter(FilterMatch.ALL, List.of(conditions), List.of(), null);
    }

    private static FilterCondition cond(FilterField field, FilterOperator operator, String value) {
        return new FilterCondition(field, operator, value);
    }

    private static FilterQueryService.FilterScope scope(List<UUID> libraryIds, UUID libraryId) {
        return new FilterQueryService.FilterScope(libraryIds, libraryId, LISTENER);
    }

    private static FilterQueryService.ChunkPage chunk(String shuffleSeed, Instant asOf, int limit, int offset) {
        return new FilterQueryService.ChunkPage(shuffleSeed, null, asOf, limit, offset);
    }

    private static List<UUID> ids(Page<?> page) {
        return page.getContent().stream()
                .map(e -> ((app.ister.core.entity.BaseEntity) e).getId())
                .toList();
    }

    /** One library with three tracks: rated rock track, played pop track, bare track. */
    private record MusicFixture(LibraryEntity library, TrackEntity rock, TrackEntity pop, TrackEntity bare,
                                UserEntity user) {
    }

    private MusicFixture musicFixture(String suffix) {
        UserEntity user = em.persist(UserEntity.builder().externalId(LISTENER).build());
        LibraryEntity library = em.persist(LibraryEntity.builder()
                .libraryType(LibraryType.MUSIC).name("Music-f-" + suffix).build());
        PersonEntity artist = em.persist(PersonEntity.builder().name("Glass Artist " + suffix).build());
        AlbumEntity album = em.persist(AlbumEntity.builder()
                .libraryEntity(library).personEntity(artist).name("Glass Album " + suffix).releaseYear(2005).build());
        TrackEntity rock = em.persist(TrackEntity.builder()
                .albumEntity(album).personEntity(artist).number(1).discNumber(1).build());
        TrackEntity pop = em.persist(TrackEntity.builder()
                .albumEntity(album).personEntity(artist).number(2).discNumber(1).build());
        TrackEntity bare = em.persist(TrackEntity.builder()
                .albumEntity(album).personEntity(artist).number(3).discNumber(1).build());
        em.persist(MetadataEntity.builder().trackEntity(rock).title("Heart of Glass").genre("Rock").build());
        em.persist(MetadataEntity.builder().trackEntity(pop).title("Glass Onion").genre("Pop").build());
        em.persist(RatingEntity.builder().userEntity(user).trackEntity(rock).value(9).build());
        em.persist(WatchStatusEntity.builder()
                .playQueueItemId(UUID.randomUUID()).userEntity(user).trackEntity(pop).watched(true).build());
        em.flush();
        return new MusicFixture(library, rock, pop, bare, user);
    }

    @Test
    void trackPageFiltersOnMetadataRatingAndPlayStats() {
        MusicFixture f = musicFixture("a");
        PageRequest page = PageRequest.of(0, 10);
        List<UUID> libraries = List.of(f.library().getId());

        // Metadata title contains, matched case-insensitively over the metadata rows;
        // the NAME sort runs over the metadata titles ("Glass Onion" < "Heart of Glass").
        assertEquals(List.of(f.pop.getId(), f.rock.getId()),
                ids(subject.page(FilterKind.TRACK, all(cond(FilterField.TITLE, FilterOperator.CONTAINS, "glass")),
                        SortingEnum.NAME, SortingOrder.ASCENDING, scope(libraries, null), page)));

        // NOT_CONTAINS means "no metadata row matches": the metadata-less track qualifies too.
        assertEquals(new HashSet<>(List.of(f.pop.getId(), f.bare.getId())),
                new HashSet<>(ids(subject.page(FilterKind.TRACK,
                        all(cond(FilterField.TITLE, FilterOperator.NOT_CONTAINS, "heart")),
                        SortingEnum.NAME, SortingOrder.ASCENDING, scope(libraries, null), page))));

        // The calling user's rating.
        assertEquals(List.of(f.rock.getId()),
                ids(subject.page(FilterKind.TRACK, all(cond(FilterField.RATING, FilterOperator.GREATER_THAN, "8")),
                        SortingEnum.NAME, SortingOrder.ASCENDING, scope(libraries, null), page)));

        // Play stats: never played = play count 0.
        assertEquals(new HashSet<>(List.of(f.rock.getId(), f.bare.getId())),
                new HashSet<>(ids(subject.page(FilterKind.TRACK,
                        all(cond(FilterField.PLAY_COUNT, FilterOperator.EQUALS, "0")),
                        SortingEnum.NAME, SortingOrder.ASCENDING, scope(libraries, null), page))));
        assertEquals(List.of(f.pop.getId()),
                ids(subject.page(FilterKind.TRACK,
                        all(cond(FilterField.LAST_PLAYED_AT, FilterOperator.IN_LAST_DAYS, "1")),
                        SortingEnum.NAME, SortingOrder.ASCENDING, scope(libraries, null), page)));

        // ANY group: rated-9 OR pop genre.
        MediaFilter any = new MediaFilter(FilterMatch.ANY, List.of(
                cond(FilterField.RATING, FilterOperator.EQUALS, "9"),
                cond(FilterField.GENRE, FilterOperator.CONTAINS, "pop")), List.of(), null);
        assertEquals(new HashSet<>(List.of(f.rock.getId(), f.pop.getId())),
                new HashSet<>(ids(subject.page(FilterKind.TRACK, any,
                        SortingEnum.NAME, SortingOrder.ASCENDING, scope(libraries, null), page))));

        // Nested group: year < 2010 AND (genre rock OR genre pop).
        MediaFilter nested = new MediaFilter(FilterMatch.ALL,
                List.of(cond(FilterField.RELEASE_YEAR, FilterOperator.LESS_THAN, "2010")),
                List.of(new MediaFilter(FilterMatch.ANY, List.of(
                        cond(FilterField.GENRE, FilterOperator.EQUALS, "Rock"),
                        cond(FilterField.GENRE, FilterOperator.EQUALS, "Pop")), List.of(), null)),
                null);
        assertEquals(new HashSet<>(List.of(f.rock.getId(), f.pop.getId())),
                new HashSet<>(ids(subject.page(FilterKind.TRACK, nested,
                        SortingEnum.NAME, SortingOrder.ASCENDING, scope(libraries, null), page))));
    }

    @Test
    void limitCapsTheTotalAndTheLastPage() {
        MusicFixture f = musicFixture("b");
        MediaFilter limited = new MediaFilter(FilterMatch.ALL, List.of(), List.of(), 2);

        Page<Object> first = subject.page(FilterKind.TRACK, limited, SortingEnum.NAME, SortingOrder.ASCENDING,
                scope(List.of(f.library().getId()), null), PageRequest.of(0, 10));
        assertEquals(2, first.getTotalElements(), "three tracks match, the limit caps at two");
        assertEquals(2, first.getContent().size());
    }

    @Test
    void libraryScopingKeepsForeignLibrariesOut() {
        MusicFixture visible = musicFixture("c");
        MusicFixture other = musicFixture("d");
        MediaFilter everything = new MediaFilter(FilterMatch.ALL, List.of(), List.of(), null);

        // Allowed set: only the first library.
        assertEquals(3, subject.page(FilterKind.TRACK, everything, SortingEnum.NAME, SortingOrder.ASCENDING,
                scope(List.of(visible.library().getId()), null), PageRequest.of(0, 10)).getTotalElements());
        // Admin (null allowed set) with an explicit scope on the other library.
        assertEquals(3, subject.page(FilterKind.TRACK, everything, SortingEnum.NAME, SortingOrder.ASCENDING,
                scope(null, other.library().getId()), PageRequest.of(0, 10)).getTotalElements());
        // Empty allowed set sees nothing.
        assertEquals(0, subject.page(FilterKind.TRACK, everything, SortingEnum.NAME, SortingOrder.ASCENDING,
                scope(List.of(), null), PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void movieFiltersCoverWatchedDurationAndDateAdded() {
        UserEntity user = em.persist(UserEntity.builder().externalId(LISTENER).build());
        LibraryEntity library = em.persist(LibraryEntity.builder()
                .libraryType(LibraryType.MOVIE).name("Movies-f").build());
        MovieEntity seen = em.persist(MovieEntity.builder()
                .libraryEntity(library).name("Seen").releaseYear(1999).build());
        MovieEntity unseen = em.persist(MovieEntity.builder()
                .libraryEntity(library).name("Unseen").releaseYear(2015).build());
        em.persist(WatchStatusEntity.builder()
                .playQueueItemId(UUID.randomUUID()).userEntity(user).movieEntity(seen).watched(true).build());
        MediaFileEntity file = MediaFileEntity.builder().size(1).path("movies/seen.mkv")
                .durationInMilliseconds(90L * 60000).build();
        file.setDirectoryEntity(persistDirectory("movies-f"));
        file.setMovieEntity(seen);
        em.persist(file);
        em.flush();
        List<UUID> libraries = List.of(library.getId());
        PageRequest page = PageRequest.of(0, 10);

        assertEquals(List.of(seen.getId()),
                ids(subject.page(FilterKind.MOVIE, all(cond(FilterField.WATCHED, FilterOperator.EQUALS, "true")),
                        SortingEnum.NAME, SortingOrder.ASCENDING, scope(libraries, null), page)));
        assertEquals(List.of(unseen.getId()),
                ids(subject.page(FilterKind.MOVIE, all(cond(FilterField.WATCHED, FilterOperator.EQUALS, "false")),
                        SortingEnum.NAME, SortingOrder.ASCENDING, scope(libraries, null), page)));
        assertEquals(List.of(seen.getId()),
                ids(subject.page(FilterKind.MOVIE, all(cond(FilterField.RELEASE_YEAR, FilterOperator.LESS_THAN, "2010")),
                        SortingEnum.NAME, SortingOrder.ASCENDING, scope(libraries, null), page)));
        assertEquals(List.of(seen.getId()),
                ids(subject.page(FilterKind.MOVIE,
                        all(cond(FilterField.DURATION, FilterOperator.GREATER_THAN, String.valueOf(60L * 60000))),
                        SortingEnum.NAME, SortingOrder.ASCENDING, scope(libraries, null), page)));
        assertEquals(2, subject.page(FilterKind.MOVIE,
                        all(cond(FilterField.DATE_ADDED, FilterOperator.IN_LAST_DAYS, "1")),
                        SortingEnum.NAME, SortingOrder.ASCENDING, scope(libraries, null), page)
                .getTotalElements());
    }

    @Test
    void chunkIdsPageTheSeededShuffleDeterministicallyAndHonourTheLimit() {
        UserEntity user = em.persist(UserEntity.builder().externalId(LISTENER).build());
        LibraryEntity library = em.persist(LibraryEntity.builder()
                .libraryType(LibraryType.MUSIC).name("Music-chunks").build());
        PersonEntity artist = em.persist(PersonEntity.builder().name("Chunk Artist").build());
        AlbumEntity album = em.persist(AlbumEntity.builder()
                .libraryEntity(library).personEntity(artist).name("Chunks").releaseYear(2010).build());
        List<UUID> trackIds = IntStream.range(0, 12)
                .mapToObj(i -> em.persist(TrackEntity.builder()
                        .albumEntity(album).personEntity(artist).number(i + 1).discNumber(1).build()).getId())
                .toList();
        em.flush();
        MediaFilter everything = new MediaFilter(FilterMatch.ALL, List.of(), List.of(), null);
        List<UUID> libraries = List.of(library.getId());
        String seed = "filter-seed";

        List<UUID> paged = new ArrayList<>();
        paged.addAll(subject.chunkIds(FilterKind.TRACK, everything, null, null,
                scope(libraries, null), chunk(seed, Instant.now(), 5, 0)));
        paged.addAll(subject.chunkIds(FilterKind.TRACK, everything, null, null,
                scope(libraries, null), chunk(seed, Instant.now(), 5, 5)));
        paged.addAll(subject.chunkIds(FilterKind.TRACK, everything, null, null,
                scope(libraries, null), chunk(seed, Instant.now(), 5, 10)));
        assertEquals(subject.chunkIds(FilterKind.TRACK, everything, null, null,
                scope(libraries, null), chunk(seed, Instant.now(), 12, 0)), paged,
                "chunked pages must continue the same permutation");
        assertEquals(new HashSet<>(trackIds), new HashSet<>(paged), "every track exactly once");

        // Ordered chunking follows the pinned sort; the limit truncates the source.
        MediaFilter limited = new MediaFilter(FilterMatch.ALL, List.of(), List.of(), 7);
        assertEquals(5, subject.chunkIds(FilterKind.TRACK, limited, SortingEnum.DATE_CREATED,
                SortingOrder.ASCENDING, scope(libraries, null), chunk(null, Instant.now(), 5, 0)).size());
        assertEquals(2, subject.chunkIds(FilterKind.TRACK, limited, SortingEnum.DATE_CREATED,
                        SortingOrder.ASCENDING, scope(libraries, null), chunk(null, Instant.now(), 5, 5)).size(),
                "the second chunk is truncated at the limit, marking the source exhausted");
        assertTrue(subject.chunkIds(FilterKind.TRACK, limited, SortingEnum.DATE_CREATED,
                        SortingOrder.ASCENDING, scope(libraries, null), chunk(null, Instant.now(), 5, 7)).isEmpty());

        // The asOf freeze: plays after the freeze point don't change a play-derived filter.
        Instant asOf = Instant.now();
        em.persistAndFlush(WatchStatusEntity.builder()
                .playQueueItemId(UUID.randomUUID()).userEntity(user)
                .trackEntity(em.find(TrackEntity.class, trackIds.getFirst())).watched(true).build());
        MediaFilter neverPlayed = all(cond(FilterField.PLAY_COUNT, FilterOperator.EQUALS, "0"));
        assertEquals(12, subject.chunkIds(FilterKind.TRACK, neverPlayed, SortingEnum.NAME,
                        SortingOrder.ASCENDING, scope(libraries, null), chunk(null, asOf, 50, 0)).size(),
                "the frozen evaluation does not see the later play");
        assertEquals(11, subject.chunkIds(FilterKind.TRACK, neverPlayed, SortingEnum.NAME,
                        SortingOrder.ASCENDING, scope(libraries, null), chunk(null, Instant.now(), 50, 0)).size());
    }

    @Test
    void indexOfRanksAnItemInTheFilterOrderWithoutReturningTheOnesBeforeIt() {
        em.persist(UserEntity.builder().externalId(LISTENER).build());
        LibraryEntity library = em.persist(LibraryEntity.builder()
                .libraryType(LibraryType.MUSIC).name("Music-index").build());
        PersonEntity artist = em.persist(PersonEntity.builder().name("Index Artist").build());
        AlbumEntity album = em.persist(AlbumEntity.builder()
                .libraryEntity(library).personEntity(artist).name("Indexes").releaseYear(2010).build());
        // Titles sort as "Track 00" … "Track 19", so position N is track N.
        List<UUID> trackIds = IntStream.range(0, 20)
                .mapToObj(i -> {
                    TrackEntity track = em.persist(TrackEntity.builder()
                            .albumEntity(album).personEntity(artist).number(i + 1).discNumber(1).build());
                    em.persist(MetadataEntity.builder().trackEntity(track)
                            .title(String.format("Track %02d", i)).build());
                    return track.getId();
                })
                .toList();
        em.flush();
        MediaFilter everything = new MediaFilter(FilterMatch.ALL, List.of(), List.of(), null);
        List<UUID> libraries = List.of(library.getId());
        Instant asOf = Instant.now();

        assertEquals(0, subject.indexOf(FilterKind.TRACK, everything, SortingEnum.NAME, SortingOrder.ASCENDING,
                scope(libraries, null), asOf, trackIds.getFirst()));
        assertEquals(17, subject.indexOf(FilterKind.TRACK, everything, SortingEnum.NAME, SortingOrder.ASCENDING,
                scope(libraries, null), asOf, trackIds.get(17)));
        assertEquals(2, subject.indexOf(FilterKind.TRACK, everything, SortingEnum.NAME, SortingOrder.DESCENDING,
                        scope(libraries, null), asOf, trackIds.get(17)),
                "the position follows the pinned sort direction");

        // A per-user field binds the caller inside the wrapped ranking query too.
        MediaFilter neverPlayed = all(cond(FilterField.PLAY_COUNT, FilterOperator.EQUALS, "0"));
        assertEquals(9, subject.indexOf(FilterKind.TRACK, neverPlayed, SortingEnum.NAME, SortingOrder.ASCENDING,
                scope(libraries, null), asOf, trackIds.get(9)));

        // Not matching, out of the library scope, and past the filter's own limit all read as absent.
        MediaFilter onlyTrack00 = all(cond(FilterField.TITLE, FilterOperator.CONTAINS, "Track 00"));
        assertEquals(-1, subject.indexOf(FilterKind.TRACK, onlyTrack00, SortingEnum.NAME, SortingOrder.ASCENDING,
                scope(libraries, null), asOf, trackIds.get(5)));
        assertEquals(-1, subject.indexOf(FilterKind.TRACK, everything, SortingEnum.NAME, SortingOrder.ASCENDING,
                scope(List.of(), null), asOf, trackIds.getFirst()));
        MediaFilter firstFive = new MediaFilter(FilterMatch.ALL, List.of(), List.of(), 5);
        assertEquals(4, subject.indexOf(FilterKind.TRACK, firstFive, SortingEnum.NAME, SortingOrder.ASCENDING,
                scope(libraries, null), asOf, trackIds.get(4)));
        assertEquals(-1, subject.indexOf(FilterKind.TRACK, firstFive, SortingEnum.NAME, SortingOrder.ASCENDING,
                        scope(libraries, null), asOf, trackIds.get(5)),
                "the limit cuts the source short, so item 5 is not part of it");
    }

    @Test
    void validateRejectsMismatchedFieldsOperatorsAndValues() {
        MediaFilter playCountOnMovie = all(cond(FilterField.PLAY_COUNT, FilterOperator.EQUALS, "1"));
        assertThrows(IllegalArgumentException.class,
                () -> subject.validate(FilterKind.MOVIE, playCountOnMovie), "play count is track-only");
        MediaFilter lessThanOnTitle = all(cond(FilterField.TITLE, FilterOperator.LESS_THAN, "abc"));
        assertThrows(IllegalArgumentException.class,
                () -> subject.validate(FilterKind.TRACK, lessThanOnTitle), "string fields have no less-than");
        MediaFilter nonNumericRating = all(cond(FilterField.RATING, FilterOperator.EQUALS, "high"));
        assertThrows(IllegalArgumentException.class,
                () -> subject.validate(FilterKind.TRACK, nonNumericRating), "rating needs a number");
        MediaFilter blankValue = all(cond(FilterField.TITLE, FilterOperator.CONTAINS, " "));
        assertThrows(IllegalArgumentException.class,
                () -> subject.validate(FilterKind.TRACK, blankValue), "a blank value is no value");
        MediaFilter nestedLimit = new MediaFilter(FilterMatch.ALL, List.of(), List.of(
                new MediaFilter(FilterMatch.ALL, List.of(), List.of(), 5)), null);
        assertThrows(IllegalArgumentException.class,
                () -> subject.validate(FilterKind.TRACK, nestedLimit), "a limit below the top level is rejected");
        // A valid definition passes.
        subject.validate(FilterKind.TRACK, all(cond(FilterField.LAST_PLAYED_AT, FilterOperator.BEFORE, "2024-01-01")));
    }

    private app.ister.core.entity.DirectoryEntity persistDirectory(String name) {
        app.ister.core.entity.NodeEntity node = em.persist(app.ister.core.entity.NodeEntity.builder()
                .name("node-" + name).url("http://localhost").build());
        return em.persist(app.ister.core.entity.DirectoryEntity.builder()
                .nodeEntity(node).name(name).path("/data/" + name)
                .directoryType(app.ister.core.enums.DirectoryType.LIBRARY).build());
    }
}
