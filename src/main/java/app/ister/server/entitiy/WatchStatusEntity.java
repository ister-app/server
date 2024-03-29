package app.ister.server.entitiy;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class WatchStatusEntity extends BaseEntity {

    /**
     * The play queue item id. Every user can have multiple WatchStatusEntity of the same media item.
     * When watching a media item it needs to update the correct WatchStatusEntity.
     */
    @NotNull
    private UUID playQueueItemId;

    @NotNull
    @ManyToOne
    private UserEntity userEntity;

    @NotNull
    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private EpisodeEntity episodeEntity;

    @NotNull
    private boolean watched;

    private long progressInMilliseconds;
}
