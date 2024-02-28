package app.ister.server.entitiy;

import app.ister.server.enums.EventType;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class ServerEventEntity extends BaseEntity {

    @NotNull
    private EventType eventType;

    @NotNull
    @ManyToOne
    private DiskEntity diskEntity;

    @ManyToOne
    private EpisodeEntity episodeEntity;

    @NotNull
    private String path;

}
