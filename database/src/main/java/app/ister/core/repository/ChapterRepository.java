package app.ister.core.repository;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChapterRepository extends JpaRepository<ChapterEntity, UUID> {
    Optional<ChapterEntity> findByBookEntityAndNumber(BookEntity bookEntity, int number);

    List<ChapterEntity> findByBookEntity_Id(UUID bookId, Sort sort);

    /**
     * Returns a page of chapter IDs of a book in natural play order.
     */
    @Query(value = """
            SELECT c.id FROM chapter_entity c
            WHERE c.book_entity_id = :bookId
            ORDER BY c.number, c.id
            LIMIT :limit OFFSET :offset""", nativeQuery = true)
    List<UUID> findChapterIdsForBookOrdered(@Param("bookId") UUID bookId, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * The chapter a user should continue an audiobook with: the first chapter after the given number
     * they have not finished. Empty when the book is finished. The audiobook twin of
     * {@link EpisodeRepository#findNextUnwatchedEpisodeId}.
     */
    @Query(value = """
            SELECT c.id FROM chapter_entity c
            WHERE c.book_entity_id = :bookId
              AND c.number > :afterNumber
              AND NOT EXISTS (SELECT 1 FROM watch_status_entity w
                              WHERE w.chapter_entity_id = c.id AND w.user_entity_id = :userId AND w.watched)
            ORDER BY c.number, c.id
            LIMIT 1""", nativeQuery = true)
    List<UUID> findNextUnfinishedChapterId(@Param("bookId") UUID bookId,
                                           @Param("userId") UUID userId,
                                           @Param("afterNumber") int afterNumber);
}
