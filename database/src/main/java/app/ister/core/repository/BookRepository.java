package app.ister.core.repository;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.enums.LibraryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookRepository extends JpaRepository<BookEntity, UUID> {
    Optional<BookEntity> findByPersonEntityAndNameAndPathYear(PersonEntity personEntity, String name, int pathYear);

    /**
     * Used when the book file or directory carries no "(YYYY)" suffix: the year is then unknown
     * rather than zero, so it cannot take part in matching. Oldest first, so the book that already
     * holds the chapters or epub wins over any later row with the same name.
     */
    Optional<BookEntity> findFirstByPersonEntityAndNameOrderByDateCreatedAsc(PersonEntity personEntity, String name);

    Page<BookEntity> findByPersonEntity(PersonEntity personEntity, Pageable pageable);

    Page<BookEntity> findByLibraryEntityId(UUID libraryId, Pageable pageable);

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
}
