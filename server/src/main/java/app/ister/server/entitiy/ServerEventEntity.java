package app.ister.server.entitiy;

import app.ister.server.enums.EventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class ServerEventEntity extends BaseEntity {

    @Column(nullable = false)
    private EventType eventType;

    @ManyToOne
    private DirectoryEntity directoryEntity;

    @ManyToOne
    private EpisodeEntity episodeEntity;

    private String path;

}
