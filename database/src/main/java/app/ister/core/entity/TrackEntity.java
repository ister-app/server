package app.ister.core.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"albumEntityId", "number", "discNumber"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class TrackEntity extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @Setter
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

    /** Primary artist plus featured guests; the primary one is also {@link #personEntity}. */
    @OneToMany(cascade = CascadeType.ALL, mappedBy = "trackEntity", orphanRemoval = true)
    @OrderBy("position ASC")
    private List<TrackCreditEntity> credits;
}
