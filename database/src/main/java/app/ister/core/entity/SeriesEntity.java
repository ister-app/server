package app.ister.core.entity;

import app.ister.core.enums.ReadingDirection;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * A book series ("De Grijze Jager") or comic series ("Attack on Titan"). Book series are unique
 * per author (a same-named series by another author is a different series) and detected from epub
 * metadata (calibre / EPUB3 belongs-to-collection) or a shared "Series - Title" prefix across an
 * author's books. Comic series have no author ({@code personEntity} NULL): the COMIC library
 * layout is series-first, one directory per series, identity {@code (library, name, startYear)}
 * via a partial unique index (V23).
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"personEntityId", "name"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class SeriesEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private LibraryEntity libraryEntity;

    /** The author; NULL for comic series. */
    @ManyToOne
    private PersonEntity personEntity;

    @Setter
    @Column(nullable = false)
    private String name;

    /** Start year from the "(YYYY)" suffix on the comic series directory; 0 = none. */
    @Setter
    @Column(nullable = false)
    private int startYear;

    /**
     * Detected default reading direction: RTL for manga (ComicInfo.xml {@code Manga} tag, or the
     * Wikidata enrichment). NULL = no signal, which resolves to LTR. A user preference row
     * ({@link UserSeriesPreferenceEntity}) overrides this.
     */
    @Setter
    @Enumerated(EnumType.STRING)
    private ReadingDirection defaultReadingDirection;

    /**
     * Series order: seriesIndex ascending; books with an unknown position sort last (PostgreSQL
     * puts NULLs last on ASC).
     */
    @OneToMany(fetch = FetchType.LAZY, mappedBy = "seriesEntity")
    @OrderBy("seriesIndex ASC, name ASC")
    private List<BookEntity> bookEntities;

    /** Series-level metadata (Wikipedia description per language); comics only for now. */
    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "seriesEntity")
    private List<MetadataEntity> metadataEntities;

    /** Series-level artwork (folder.jpg in the series directory, or a wiki thumbnail). */
    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "seriesEntity")
    private List<ImageEntity> imageEntities;
}
