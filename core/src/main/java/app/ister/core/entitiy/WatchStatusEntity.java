package app.ister.core.entitiy;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"playQueueItemId", "userEntityId", "movieEntityId", "episodeEntityId"}))
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class WatchStatusEntity extends BaseEntity {

    /**
     * The play queue item id. Every user can have multiple WatchStatusEntity of the same media item.
     * When watching a media item it needs to update the correct WatchStatusEntity.
     */
    @Column(nullable = false)
    private UUID playQueueItemId;

    @ManyToOne(optional = false)
    private UserEntity userEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private MovieEntity movieEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private EpisodeEntity episodeEntity;

    @Column(nullable = false)
    private boolean watched;

    private long progressInMilliseconds;
}
