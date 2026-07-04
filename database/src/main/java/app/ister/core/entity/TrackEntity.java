package app.ister.core.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"albumEntityId", "number", "discNumber"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class TrackEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private PersonEntity personEntity;

    @ManyToOne(optional = false)
    private AlbumEntity albumEntity;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false)
    private int discNumber;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "trackEntity")
    private List<MediaFileEntity> mediaFileEntities;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "trackEntity")
    private List<MetadataEntity> metadataEntities;
}
