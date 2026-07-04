package app.ister.core.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"libraryEntityId", "name"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class PersonEntity extends BaseEntity {

    // Nullable: persons created from TMDB cast credits don't belong to a music library.
    @Setter
    @ManyToOne
    private LibraryEntity libraryEntity;

    @Setter
    @Column(nullable = false)
    private String name;

    @Setter
    @Column(unique = true)
    private Long tmdbId;

    @Setter
    private Integer birthYear;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "personEntity")
    @OrderBy("name ASC")
    private List<AlbumEntity> albumEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "personEntity")
    private List<ImageEntity> imageEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "personEntity")
    private List<MetadataEntity> metadataEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "personEntity")
    private List<CreditEntity> creditEntities;
}
