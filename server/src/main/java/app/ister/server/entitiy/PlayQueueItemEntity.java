package app.ister.server.entitiy;


import app.ister.server.enums.MediaType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class PlayQueueItemEntity extends BaseEntity {

    @Column(nullable = false)
    private UUID itemId;

    @Column(nullable = false)
    private MediaType type;
}
