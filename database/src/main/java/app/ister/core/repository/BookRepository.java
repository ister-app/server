package app.ister.core.repository;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.enums.LibraryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookRepository extends JpaRepository<BookEntity, UUID> {
    Optional<BookEntity> findByPersonEntityAndNameAndPathYear(PersonEntity personEntity, String name, int pathYear);

    /** Comic volume identity: comics have no author, so the series scopes the name. */
    Optional<BookEntity> findBySeriesEntityAndNameAndPathYear(SeriesEntity seriesEntity, String name, int pathYear);

    /**
     * Race-safe comic volume create: multiple formats of one volume (pdf/cbz/epub with the same
     * basename) are scanned by parallel consumers, so a find-then-save loses and poisons the whole
     * transaction on the {@code book_entity_comic_identity} unique index. Returns 1 when this call
     * inserted the row, 0 when a concurrent transaction beat it to it.
     */
    @Modifying
    @Query(value = """
            INSERT INTO book_entity (id, date_created, date_updated, library_entity_id, series_entity_id,
                                     name, title, series_index, path_year, release_year)
            VALUES (:id, now(), now(), :libraryId, :seriesId, :name, :title, :seriesIndex, :pathYear, :releaseYear)
            ON CONFLICT (series_entity_id, name, path_year) WHERE person_entity_id IS NULL DO NOTHING
            """, nativeQuery = true)
    int insertComicVolumeIfAbsent(@Param("id") UUID id,
                                  @Param("libraryId") UUID libraryId,
                                  @Param("seriesId") UUID seriesId,
                                  @Param("name") String name,
                                  @Param("title") String title,
                                  @Param("seriesIndex") Double seriesIndex,
                                  @Param("pathYear") int pathYear,
                                  @Param("releaseYear") int releaseYear);

    /**
     * The comic volume to continue a series with after finishing one: the first volume in series
     * order (seriesIndex ascending, unknown positions last, then name) after the given position
     * that the user has not finished. Mirrors EpisodeRepository.findNextUnwatchedEpisodeId. The
     * position is passed pre-decomposed: nullRank 0 = the finished volume had a seriesIndex,
     * 1 = it had none, -1 = before the first volume.
     */
    @Query(value = """
            SELECT b.id FROM book_entity b
            WHERE b.series_entity_id = :seriesId
              AND (CASE WHEN b.series_index IS NULL THEN 1 ELSE 0 END, COALESCE(b.series_index, 0), b.name)
                  > (:afterNullRank, :afterIndex, :afterName)
              AND NOT EXISTS (SELECT 1 FROM watch_status_entity w
                              WHERE w.book_entity_id = b.id AND w.user_entity_id = :userId AND w.watched)
            ORDER BY b.series_index ASC NULLS LAST, b.name ASC
            LIMIT 1""", nativeQuery = true)
    List<UUID> findNextUnfinishedVolumeId(@Param("seriesId") UUID seriesId,
                                          @Param("userId") UUID userId,
                                          @Param("afterNullRank") int afterNullRank,
                                          @Param("afterIndex") double afterIndex,
                                          @Param("afterName") String afterName);

    /**
     * Used when the book file or directory carries no "(YYYY)" suffix: the year is then unknown
     * rather than zero, so it cannot take part in matching. Oldest first, so the book that already
     * holds the chapters or epub wins over any later row with the same name.
     */
    Optional<BookEntity> findFirstByPersonEntityAndNameOrderByDateCreatedAsc(PersonEntity personEntity, String name);

    Page<BookEntity> findByPersonEntity(PersonEntity personEntity, Pageable pageable);

    Page<BookEntity> findByPersonEntityAndLibraryEntityIdIn(PersonEntity personEntity, Collection<UUID> libraryIds, Pageable pageable);

    Page<BookEntity> findByLibraryEntityId(UUID libraryId, Pageable pageable);

    Page<BookEntity> findByLibraryEntityIdIn(Collection<UUID> libraryIds, Pageable pageable);

    List<BookEntity> findByPersonEntityId(UUID personId);

    List<BookEntity> findByLibraryEntity_LibraryType(LibraryType libraryType);

    List<BookEntity> findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType libraryType);

    List<BookEntity> findByLibraryEntity_LibraryTypeAndImageEntitiesIsEmpty(LibraryType libraryType);

    /**
     * Books that have not been enriched by Open Library yet — the analyze backfill re-dispatches
     * these so descriptions, covers and the original publication year get filled in.
     */
    @Query("""
            select b from BookEntity b
            where b.libraryEntity.libraryType = :libraryType
              and not exists (select m from MetadataEntity m
                              where m.bookEntity = b and m.sourceUri like 'openlibrary://%')""")
    List<BookEntity> findBooksWithoutOpenLibraryMetadata(LibraryType libraryType);

    /**
     * Series books the Wikidata enrichment could still improve: the series position is unknown, or
     * the original publication year has not been resolved yet. The analyze backfill re-dispatches
     * these; a book Wikidata simply does not know stays in this set and is retried per analyze,
     * mirroring the Open Library behaviour above.
     */
    @Query("""
            select b from BookEntity b
            where b.libraryEntity.libraryType = :libraryType
              and b.seriesEntity is not null
              and (b.seriesIndex is null
                   or not exists (select m from MetadataEntity m
                                  where m.bookEntity = b and m.sourceUri like 'wikidata://%'))""")
    List<BookEntity> findSeriesBooksMissingWikidataInfo(LibraryType libraryType);
}
