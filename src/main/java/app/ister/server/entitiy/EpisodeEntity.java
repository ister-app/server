package app.ister.server.entitiy;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.sql.Timestamp;
import java.util.List;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class EpisodeEntity extends BaseEntity {

    @NotNull
    @ManyToOne
    private ShowEntity showEntity;

    @NotNull
    @ManyToOne
    private SeasonEntity seasonEntity;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "episodeEntity")
    private List<MediaFileEntity> mediaFileEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "episodeEntity")
    private List<ImageEntity> imagesEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "episodeEntity")
    private List<MetadataEntity> metadataEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "episodeEntity")
    @OrderBy("dateUpdated DESC")
    private List<WatchStatusEntity> watchStatusEntities;

    @NotNull
    private int number;
}
