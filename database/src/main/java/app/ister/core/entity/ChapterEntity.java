package app.ister.core.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * One audiobook chapter of a {@link BookEntity}, backed by an audio file. The chapter number comes
 * from the numeric filename prefix and may be zero-based; only the ordering matters.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"bookEntityId", "number"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class ChapterEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private PersonEntity personEntity;

    @ManyToOne(optional = false)
    private BookEntity bookEntity;

    @Column(nullable = false)
    private int number;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "chapterEntity")
    private List<MediaFileEntity> mediaFileEntities;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "chapterEntity")
    private List<MetadataEntity> metadataEntities;
}
