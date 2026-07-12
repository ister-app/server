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
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"personEntityId", "name", "releaseYear"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class BookEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private LibraryEntity libraryEntity;

    @ManyToOne(optional = false)
    private PersonEntity personEntity;

    @Setter
    @Column(nullable = false)
    private String name;

    @Setter
    @Column(nullable = false)
    private int releaseYear;

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
