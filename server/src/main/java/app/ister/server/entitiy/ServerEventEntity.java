package app.ister.server.entitiy;

import app.ister.server.enums.EventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class ServerEventEntity extends BaseEntity {

    @Column(nullable = false)
    private EventType eventType;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private Boolean failed;

    @ManyToOne
    private DirectoryEntity directoryEntity;

    @ManyToOne
    private EpisodeEntity episodeEntity;

    private String path;

    /**
     * Json string with data for the event.
     */
    @Column(columnDefinition = "text")
    private String data;
}
