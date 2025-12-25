package app.ister.core.entitiy;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"libraryEntityId", "name", "releaseYear"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class ShowEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private LibraryEntity libraryEntity;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int releaseYear;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "showEntity")
    @OrderBy("number ASC")
    private List<SeasonEntity> seasonEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "showEntity")
    private List<ImageEntity> imageEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "showEntity")
    private List<MetadataEntity> metadataEntities;
}
