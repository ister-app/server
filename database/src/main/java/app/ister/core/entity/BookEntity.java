package app.ister.core.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * A logical book. The different formats (epub, audiobook chapters, epub with media overlays) are
 * attachments of one book: epub files link to the book via {@link MediaFileEntity#getBookEntity()},
 * audiobook audio files via {@link ChapterEntity}. The author is a {@link PersonEntity}, shared
 * with music artists and actors.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"personEntityId", "name", "pathYear"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class BookEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private LibraryEntity libraryEntity;

    /**
     * The author. NULL for comic volumes (COMIC libraries are series-first, the path carries no
     * author); comic identity is then {@code (seriesEntity, name, pathYear)} — guarded by a
     * partial unique index in V23 instead of the person-keyed unique constraint.
     */
    @ManyToOne
    private PersonEntity personEntity;

    /**
     * Name as derived from the file/directory path. Scanner identity (part of the unique
     * constraint) — never rewritten from metadata; {@link #title} carries the clean display form.
     */
    @Setter
    @Column(nullable = false)
    private String name;

    /**
     * Clean display title: {@link #name} with a known "{series} - " / "{series}: " prefix
     * stripped. NULL means "fall back to name".
     */
    @Setter
    private String title;

    /**
     * Display/sort year, recomputable with precedence path > Open Library > local metadata
     * (see ScannerHelperService#refreshBookReleaseYear). Not identity — that is {@link #pathYear}.
     */
    @Setter
    @Column(nullable = false)
    private int releaseYear;

    /**
     * The year from the "(YYYY)" suffix on the book's path (0 = none). Scanner identity: rescans
     * match on it, and a user-supplied path year always wins as the display year.
     */
    @Setter
    @Column(nullable = false)
    private int pathYear;

    @Setter
    @ManyToOne
    private SeriesEntity seriesEntity;

    /**
     * Position within the series; calibre allows fractions like 1.5. NULL when unknown.
     */
    @Setter
    private Double seriesIndex;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "bookEntity")
    @OrderBy("number ASC")
    private List<ChapterEntity> chapterEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "bookEntity")
    private List<MediaFileEntity> mediaFileEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "bookEntity")
    private List<ImageEntity> imageEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "bookEntity")
    private List<MetadataEntity> metadataEntities;
}
