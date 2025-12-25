package app.ister.core.entitiy;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"showEntityId", "seasonEntityId", "number"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class EpisodeEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private ShowEntity showEntity;

    @ManyToOne(optional = false)
    private SeasonEntity seasonEntity;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "episodeEntity")
    private List<MediaFileEntity> mediaFileEntities;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "episodeEntity")
    @OrderBy("sourceUri DESC")
    private List<ImageEntity> imagesEntities;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "episodeEntity")
    private List<MetadataEntity> metadataEntities;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "episodeEntity")
    @OrderBy("dateUpdated DESC")
    private List<WatchStatusEntity> watchStatusEntities;

    @Column(nullable = false)
    private int number;
}
