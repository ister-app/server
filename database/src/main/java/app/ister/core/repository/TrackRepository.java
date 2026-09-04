package app.ister.core.repository;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrackRepository extends JpaRepository<TrackEntity, UUID> {
    Optional<TrackEntity> findByAlbumEntityAndNumberAndDiscNumber(AlbumEntity albumEntity, int number, int discNumber);
    List<TrackEntity> findByAlbumEntity_Id(UUID albumId, Sort sort);
    List<TrackEntity> findByAlbumEntity_LibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType libraryType);

    /**
     * A page of every track of the given libraries, sorted for the library-wide browse grid.
     * The track's title and release date live in its metadata rows (possibly several per track),
     * so the metadata-based sorts aggregate with MIN over a left join rather than sorting a plain
     * column; the join with GROUP BY keeps each track a single row. Tracks without a title or
     * release date sort last in both directions. The id tie-break keeps paging stable (see
     * {@code Paging}).
     */
    default Page<TrackEntity> findInLibraries(Collection<UUID> libraryIds, SortingEnum sorting,
                                              SortingOrder sortingOrder, Pageable pageable) {
        boolean ascending = sortingOrder == SortingOrder.ASCENDING;
        return switch (sorting) {
            case NAME -> ascending ? findInLibrariesOrderByTitleAsc(libraryIds, pageable)
                    : findInLibrariesOrderByTitleDesc(libraryIds, pageable);
            case RELEASE_YEAR -> ascending ? findInLibrariesOrderByReleasedAsc(libraryIds, pageable)
                    : findInLibrariesOrderByReleasedDesc(libraryIds, pageable);
            case DATE_CREATED -> {
                Sort sort = ascending ? Sort.by("dateCreated").ascending() : Sort.by("dateCreated").descending();
                yield findByAlbumEntityLibraryEntityIdIn(libraryIds,
                        PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort.and(Sort.by("id"))));
            }
        };
    }

    String TRACKS_IN_LIBRARIES =
            " FROM TrackEntity t LEFT JOIN t.metadataEntities m WHERE t.albumEntity.libraryEntity.id IN :libraryIds ";
    String COUNT_TRACKS_IN_LIBRARIES =
            "SELECT COUNT(t) FROM TrackEntity t WHERE t.albumEntity.libraryEntity.id IN :libraryIds";

    @Query(value = "SELECT t" + TRACKS_IN_LIBRARIES + "GROUP BY t ORDER BY MIN(m.title) ASC NULLS LAST, t.id",
            countQuery = COUNT_TRACKS_IN_LIBRARIES)
    Page<TrackEntity> findInLibrariesOrderByTitleAsc(@Param("libraryIds") Collection<UUID> libraryIds, Pageable pageable);

    @Query(value = "SELECT t" + TRACKS_IN_LIBRARIES + "GROUP BY t ORDER BY MIN(m.title) DESC NULLS LAST, t.id",
            countQuery = COUNT_TRACKS_IN_LIBRARIES)
    Page<TrackEntity> findInLibrariesOrderByTitleDesc(@Param("libraryIds") Collection<UUID> libraryIds, Pageable pageable);

    @Query(value = "SELECT t" + TRACKS_IN_LIBRARIES + "GROUP BY t ORDER BY MIN(m.released) ASC NULLS LAST, t.id",
            countQuery = COUNT_TRACKS_IN_LIBRARIES)
    Page<TrackEntity> findInLibrariesOrderByReleasedAsc(@Param("libraryIds") Collection<UUID> libraryIds, Pageable pageable);

    @Query(value = "SELECT t" + TRACKS_IN_LIBRARIES + "GROUP BY t ORDER BY MIN(m.released) DESC NULLS LAST, t.id",
            countQuery = COUNT_TRACKS_IN_LIBRARIES)
    Page<TrackEntity> findInLibrariesOrderByReleasedDesc(@Param("libraryIds") Collection<UUID> libraryIds, Pageable pageable);

    Page<TrackEntity> findByAlbumEntityLibraryEntityIdIn(Collection<UUID> libraryIds, Pageable pageable);

    /**
     * A page of every track credited to an artist within the given libraries: the artist has a
     * track credit (primary or featured) on it, or owns the album it sits on. That is what puts
     * compilation and guest appearances on the artist page. Sorting works exactly like
     * {@link #findInLibraries}.
     */
    default Page<TrackEntity> findForPersonInLibraries(UUID personId, Collection<UUID> libraryIds, SortingEnum sorting,
                                                       SortingOrder sortingOrder, Pageable pageable) {
        boolean ascending = sortingOrder == SortingOrder.ASCENDING;
        return switch (sorting) {
            case NAME -> ascending ? findForPersonOrderByTitleAsc(personId, libraryIds, pageable)
                    : findForPersonOrderByTitleDesc(personId, libraryIds, pageable);
            case RELEASE_YEAR -> ascending ? findForPersonOrderByReleasedAsc(personId, libraryIds, pageable)
                    : findForPersonOrderByReleasedDesc(personId, libraryIds, pageable);
            case DATE_CREATED -> ascending ? findForPersonOrderByCreatedAsc(personId, libraryIds, pageable)
                    : findForPersonOrderByCreatedDesc(personId, libraryIds, pageable);
        };
    }

    String CREDITED_TO_PERSON =
            " WHERE (t.personEntity.id = :personId OR t.albumEntity.personEntity.id = :personId"
                    + " OR EXISTS (SELECT 1 FROM TrackCreditEntity c WHERE c.trackEntity = t AND c.personEntity.id = :personId))"
                    + " AND t.albumEntity.libraryEntity.id IN :libraryIds ";
    String TRACKS_FOR_PERSON = " FROM TrackEntity t LEFT JOIN t.metadataEntities m " + CREDITED_TO_PERSON;
    String COUNT_TRACKS_FOR_PERSON = "SELECT COUNT(t) FROM TrackEntity t " + CREDITED_TO_PERSON;

    @Query(value = "SELECT t" + TRACKS_FOR_PERSON + "GROUP BY t ORDER BY MIN(m.title) ASC NULLS LAST, t.id",
            countQuery = COUNT_TRACKS_FOR_PERSON)
    Page<TrackEntity> findForPersonOrderByTitleAsc(@Param("personId") UUID personId, @Param("libraryIds") Collection<UUID> libraryIds, Pageable pageable);

    @Query(value = "SELECT t" + TRACKS_FOR_PERSON + "GROUP BY t ORDER BY MIN(m.title) DESC NULLS LAST, t.id",
            countQuery = COUNT_TRACKS_FOR_PERSON)
    Page<TrackEntity> findForPersonOrderByTitleDesc(@Param("personId") UUID personId, @Param("libraryIds") Collection<UUID> libraryIds, Pageable pageable);

    @Query(value = "SELECT t" + TRACKS_FOR_PERSON + "GROUP BY t ORDER BY MIN(m.released) ASC NULLS LAST, t.id",
            countQuery = COUNT_TRACKS_FOR_PERSON)
    Page<TrackEntity> findForPersonOrderByReleasedAsc(@Param("personId") UUID personId, @Param("libraryIds") Collection<UUID> libraryIds, Pageable pageable);

    @Query(value = "SELECT t" + TRACKS_FOR_PERSON + "GROUP BY t ORDER BY MIN(m.released) DESC NULLS LAST, t.id",
            countQuery = COUNT_TRACKS_FOR_PERSON)
    Page<TrackEntity> findForPersonOrderByReleasedDesc(@Param("personId") UUID personId, @Param("libraryIds") Collection<UUID> libraryIds, Pageable pageable);

    @Query(value = "SELECT t FROM TrackEntity t" + CREDITED_TO_PERSON + "ORDER BY t.dateCreated ASC, t.id",
            countQuery = COUNT_TRACKS_FOR_PERSON)
    Page<TrackEntity> findForPersonOrderByCreatedAsc(@Param("personId") UUID personId, @Param("libraryIds") Collection<UUID> libraryIds, Pageable pageable);

    @Query(value = "SELECT t FROM TrackEntity t" + CREDITED_TO_PERSON + "ORDER BY t.dateCreated DESC, t.id",
            countQuery = COUNT_TRACKS_FOR_PERSON)
    Page<TrackEntity> findForPersonOrderByCreatedDesc(@Param("personId") UUID personId, @Param("libraryIds") Collection<UUID> libraryIds, Pageable pageable);

    /**
     * Returns a page of track IDs of an album in natural play order (disc number, track number).
     */
    @Query(value = """
            SELECT t.id FROM track_entity t
            WHERE t.album_entity_id = :albumId
            ORDER BY t.disc_number, t.number, t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findTrackIdsForAlbumOrdered(@Param("albumId") UUID albumId, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * Returns a page of track IDs of an album in a deterministic shuffled order derived from the seed.
     */
    @Query(value = """
            SELECT t.id FROM track_entity t
            WHERE t.album_entity_id = :albumId AND t.id <> :excludeId
            ORDER BY md5(t.id::text || :seed), t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findTrackIdsForAlbumShuffled(@Param("albumId") UUID albumId, @Param("seed") String seed, @Param("excludeId") UUID excludeId, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * The calling user's most played tracks of an artist (credited on the track, or the album artist), most played
     * first. Plays are watch-status rows: one per played play-queue item. Only plays up to
     * {@code asOf} count (on date_created — progress updates bump date_updated), so a play queue
     * paging through the ranking with its creation time as {@code asOf} sees a frozen order.
     */
    @Query(value = """
            SELECT t.id FROM track_entity t
            JOIN album_entity a ON t.album_entity_id = a.id
            JOIN watch_status_entity ws ON ws.track_entity_id = t.id AND ws.date_created <= :asOf
            JOIN user_entity u ON u.id = ws.user_entity_id AND u.external_id = :externalId
            WHERE (t.person_entity_id = :personId OR a.person_entity_id = :personId
                   OR EXISTS (SELECT 1 FROM track_credit_entity tc WHERE tc.track_entity_id = t.id AND tc.person_entity_id = :personId))
            GROUP BY t.id
            ORDER BY COUNT(ws.id) DESC, MAX(ws.date_updated) DESC, t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findTopPlayedTrackIdsForPerson(@Param("personId") UUID personId, @Param("externalId") String externalId, @Param("asOf") Instant asOf, @Param("limit") int limit, @Param("offset") int offset);

    @Query(value = """
            SELECT t.id FROM track_entity t
            JOIN album_entity a ON t.album_entity_id = a.id
            JOIN watch_status_entity ws ON ws.track_entity_id = t.id AND ws.date_created <= :asOf
            JOIN user_entity u ON u.id = ws.user_entity_id AND u.external_id = :externalId
            WHERE (t.person_entity_id = :personId OR a.person_entity_id = :personId
                   OR EXISTS (SELECT 1 FROM track_credit_entity tc WHERE tc.track_entity_id = t.id AND tc.person_entity_id = :personId))
              AND a.library_entity_id IN (:libraryIds)
            GROUP BY t.id
            ORDER BY COUNT(ws.id) DESC, MAX(ws.date_updated) DESC, t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findTopPlayedTrackIdsForPersonInLibraries(@Param("personId") UUID personId, @Param("externalId") String externalId, @Param("libraryIds") Collection<UUID> libraryIds, @Param("asOf") Instant asOf, @Param("limit") int limit, @Param("offset") int offset);

    /** The calling user's most recently played tracks of an artist, newest first, counting plays up to {@code asOf}. */
    @Query(value = """
            SELECT t.id FROM track_entity t
            JOIN album_entity a ON t.album_entity_id = a.id
            JOIN watch_status_entity ws ON ws.track_entity_id = t.id AND ws.date_created <= :asOf
            JOIN user_entity u ON u.id = ws.user_entity_id AND u.external_id = :externalId
            WHERE (t.person_entity_id = :personId OR a.person_entity_id = :personId
                   OR EXISTS (SELECT 1 FROM track_credit_entity tc WHERE tc.track_entity_id = t.id AND tc.person_entity_id = :personId))
            GROUP BY t.id
            ORDER BY MAX(ws.date_updated) DESC, t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findRecentlyPlayedTrackIdsForPerson(@Param("personId") UUID personId, @Param("externalId") String externalId, @Param("asOf") Instant asOf, @Param("limit") int limit, @Param("offset") int offset);

    @Query(value = """
            SELECT t.id FROM track_entity t
            JOIN album_entity a ON t.album_entity_id = a.id
            JOIN watch_status_entity ws ON ws.track_entity_id = t.id AND ws.date_created <= :asOf
            JOIN user_entity u ON u.id = ws.user_entity_id AND u.external_id = :externalId
            WHERE (t.person_entity_id = :personId OR a.person_entity_id = :personId
                   OR EXISTS (SELECT 1 FROM track_credit_entity tc WHERE tc.track_entity_id = t.id AND tc.person_entity_id = :personId))
              AND a.library_entity_id IN (:libraryIds)
            GROUP BY t.id
            ORDER BY MAX(ws.date_updated) DESC, t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findRecentlyPlayedTrackIdsForPersonInLibraries(@Param("personId") UUID personId, @Param("externalId") String externalId, @Param("libraryIds") Collection<UUID> libraryIds, @Param("asOf") Instant asOf, @Param("limit") int limit, @Param("offset") int offset);

    /** The calling user's highest rated tracks of an artist; the most recently (re)rated wins ties. */
    @Query(value = """
            SELECT t.id FROM track_entity t
            JOIN album_entity a ON t.album_entity_id = a.id
            JOIN rating_entity r ON r.track_entity_id = t.id
            JOIN user_entity u ON u.id = r.user_entity_id AND u.external_id = :externalId
            WHERE (t.person_entity_id = :personId OR a.person_entity_id = :personId
                   OR EXISTS (SELECT 1 FROM track_credit_entity tc WHERE tc.track_entity_id = t.id AND tc.person_entity_id = :personId))
            ORDER BY r.value DESC, r.date_updated DESC, t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findTopRatedTrackIdsForPerson(@Param("personId") UUID personId, @Param("externalId") String externalId, @Param("limit") int limit, @Param("offset") int offset);

    @Query(value = """
            SELECT t.id FROM track_entity t
            JOIN album_entity a ON t.album_entity_id = a.id
            JOIN rating_entity r ON r.track_entity_id = t.id
            JOIN user_entity u ON u.id = r.user_entity_id AND u.external_id = :externalId
            WHERE (t.person_entity_id = :personId OR a.person_entity_id = :personId
                   OR EXISTS (SELECT 1 FROM track_credit_entity tc WHERE tc.track_entity_id = t.id AND tc.person_entity_id = :personId))
              AND a.library_entity_id IN (:libraryIds)
            ORDER BY r.value DESC, r.date_updated DESC, t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findTopRatedTrackIdsForPersonInLibraries(@Param("personId") UUID personId, @Param("externalId") String externalId, @Param("libraryIds") Collection<UUID> libraryIds, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * The newest tracks of an artist (credited on the track, or the album artist) by the date they were added
     * to the library, newest first. Not per user. Only tracks added up to {@code asOf} count, so an ARTIST
     * play queue paging with its creation time keeps a frozen order while a scan adds tracks.
     */
    @Query(value = """
            SELECT t.id FROM track_entity t
            JOIN album_entity a ON t.album_entity_id = a.id
            WHERE (t.person_entity_id = :personId OR a.person_entity_id = :personId
                   OR EXISTS (SELECT 1 FROM track_credit_entity tc WHERE tc.track_entity_id = t.id AND tc.person_entity_id = :personId))
              AND t.date_created <= :asOf
            ORDER BY t.date_created DESC, t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findRecentlyAddedTrackIdsForPerson(@Param("personId") UUID personId, @Param("asOf") Instant asOf, @Param("limit") int limit, @Param("offset") int offset);

    @Query(value = """
            SELECT t.id FROM track_entity t
            JOIN album_entity a ON t.album_entity_id = a.id
            WHERE (t.person_entity_id = :personId OR a.person_entity_id = :personId
                   OR EXISTS (SELECT 1 FROM track_credit_entity tc WHERE tc.track_entity_id = t.id AND tc.person_entity_id = :personId))
              AND a.library_entity_id IN (:libraryIds)
              AND t.date_created <= :asOf
            ORDER BY t.date_created DESC, t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findRecentlyAddedTrackIdsForPersonInLibraries(@Param("personId") UUID personId, @Param("libraryIds") Collection<UUID> libraryIds, @Param("asOf") Instant asOf, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * A page of every track an artist is on (credited on the track, or the album artist) in a
     * deterministic shuffled order derived from the seed. Only tracks added up to {@code asOf}
     * count, so a shuffled ARTIST play queue paging with its creation time keeps a stable order
     * while a scan adds tracks.
     */
    @Query(value = """
            SELECT t.id FROM track_entity t
            JOIN album_entity a ON t.album_entity_id = a.id
            WHERE (t.person_entity_id = :personId OR a.person_entity_id = :personId
                   OR EXISTS (SELECT 1 FROM track_credit_entity tc WHERE tc.track_entity_id = t.id AND tc.person_entity_id = :personId))
              AND t.date_created <= :asOf
              AND t.id <> :excludeId
            ORDER BY md5(t.id::text || :seed), t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findShuffledTrackIdsForPerson(@Param("personId") UUID personId, @Param("asOf") Instant asOf, @Param("seed") String seed, @Param("excludeId") UUID excludeId, @Param("limit") int limit, @Param("offset") int offset);

    @Query(value = """
            SELECT t.id FROM track_entity t
            JOIN album_entity a ON t.album_entity_id = a.id
            WHERE (t.person_entity_id = :personId OR a.person_entity_id = :personId
                   OR EXISTS (SELECT 1 FROM track_credit_entity tc WHERE tc.track_entity_id = t.id AND tc.person_entity_id = :personId))
              AND a.library_entity_id IN (:libraryIds)
              AND t.date_created <= :asOf
              AND t.id <> :excludeId
            ORDER BY md5(t.id::text || :seed), t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findShuffledTrackIdsForPersonInLibraries(@Param("personId") UUID personId, @Param("libraryIds") Collection<UUID> libraryIds, @Param("asOf") Instant asOf, @Param("seed") String seed, @Param("excludeId") UUID excludeId, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * Returns a page of track IDs of a whole library in a deterministic shuffled order derived from the seed.
     */
    @Query(value = """
            SELECT t.id FROM track_entity t
            JOIN album_entity a ON t.album_entity_id = a.id
            WHERE a.library_entity_id = :libraryId AND t.id <> :excludeId
            ORDER BY md5(t.id::text || :seed), t.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findTrackIdsForLibraryShuffled(@Param("libraryId") UUID libraryId, @Param("seed") String seed, @Param("excludeId") UUID excludeId, @Param("limit") int limit, @Param("offset") int offset);
}
