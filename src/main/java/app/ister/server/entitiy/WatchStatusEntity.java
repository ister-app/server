package app.ister.server.entitiy;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"playQueueItemId", "userEntitIdy", "episodeEntityId"}))
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

    @ManyToOne(optional=false)
    private UserEntity userEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional=false)
    private EpisodeEntity episodeEntity;

    @Column(nullable = false)
    private boolean watched;

    private long progressInMilliseconds;
}
