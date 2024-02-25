package app.ister.server.entitiy;

import app.ister.server.enums.EventType;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.experimental.SuperBuilder;

@Entity
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder
@Getter
public class ServerEventEntity extends BaseEntity {

    @NonNull
    private EventType eventType;

    @NonNull
    @ManyToOne
    private DiskEntity diskEntity;

    @ManyToOne
    private EpisodeEntity episodeEntity;

    @NonNull
    private String path;

}
