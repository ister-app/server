package app.ister.server.entitiy;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
    private List<ImageEntity> imagesEntities;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "episodeEntity")
    private List<MetadataEntity> metadataEntities;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "episodeEntity")
    @OrderBy("dateUpdated DESC")
    private List<WatchStatusEntity> watchStatusEntities;

    @Column(nullable = false)
    private int number;
}
