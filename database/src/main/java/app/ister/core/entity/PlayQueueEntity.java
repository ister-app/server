package app.ister.core.entity;

import app.ister.core.enums.PlayQueueSourceType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.UUID;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class PlayQueueEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private UserEntity userEntity;

    private UUID currentItem;
    private long progressInMilliseconds;

    // Source the queue was created from; null for legacy queues (fully materialized).
    @Enumerated(EnumType.STRING)
    private PlayQueueSourceType sourceType;

    private UUID sourceId;

    @Column(nullable = false)
    private boolean shuffle;

    // Seed for the deterministic shuffle order; only set when shuffle is true.
    private String shuffleSeed;

    // Number of source items already materialized into the queue.
    @Column(nullable = false)
    private int sourceOffset;

    // True once the source has no more items to append.
    @Column(nullable = false)
    private boolean sourceExhausted;

    // Start item that was materialized up-front for a shuffled queue; excluded from chunk queries.
    private UUID sourceStartId;

    @OneToMany(cascade = {CascadeType.ALL}, mappedBy = "playQueueEntity", orphanRemoval = true)
    @OrderBy("position ASC")
    private List<PlayQueueItemEntity> items;
}
