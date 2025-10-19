package app.ister.server.entitiy;

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

    @OneToMany(cascade = {CascadeType.ALL}, mappedBy = "playQueueEntity")
    @OrderBy("position ASC")
    private List<PlayQueueItemEntity> items;
}
