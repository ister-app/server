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
public class ArtistEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private LibraryEntity libraryEntity;

    @Setter
    @Column(nullable = false)
    private String name;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "artistEntity")
    @OrderBy("name ASC")
    private List<AlbumEntity> albumEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "artistEntity")
    private List<ImageEntity> imageEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "artistEntity")
    private List<MetadataEntity> metadataEntities;
}
