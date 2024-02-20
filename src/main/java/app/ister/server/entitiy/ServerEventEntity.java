package app.ister.server.entitiy;

import app.ister.server.enums.EventType;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.*;

@Entity
@RequiredArgsConstructor(access = AccessLevel.PUBLIC)
@NoArgsConstructor
@Data
public class ServerEventEntity extends BaseEntity {

    @NonNull
    private EventType eventType;

    @NonNull
    @ManyToOne
    private DiskEntity diskEntity;

    @NonNull
    @ManyToOne
    private EpisodeEntity episodeEntity;

    @NonNull
    private String path;

}
