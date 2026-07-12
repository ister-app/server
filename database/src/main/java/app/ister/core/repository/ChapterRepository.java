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
}
