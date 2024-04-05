package app.ister.server.entitiy;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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

    @ManyToOne(optional=false)
    private LibraryEntity libraryEntity;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int releaseYear;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "showEntity")
    private List<ImageEntity> imageEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "showEntity")
    private List<MetadataEntity> metadataEntities;
}
