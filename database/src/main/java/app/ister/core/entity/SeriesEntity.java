package app.ister.core.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * A book series ("De Grijze Jager"). Unique per author: a same-named series by another author is
 * a different series. Detected from epub metadata (calibre / EPUB3 belongs-to-collection) or from
 * a shared "Series - Title" prefix across an author's books; standalone books have no series.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"personEntityId", "name"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class SeriesEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private LibraryEntity libraryEntity;

    @ManyToOne(optional = false)
    private PersonEntity personEntity;

    @Setter
    @Column(nullable = false)
    private String name;

    /**
     * Series order: seriesIndex ascending; books with an unknown position sort last (PostgreSQL
     * puts NULLs last on ASC).
     */
    @OneToMany(fetch = FetchType.LAZY, mappedBy = "seriesEntity")
    @OrderBy("seriesIndex ASC, name ASC")
    private List<BookEntity> bookEntities;
}
