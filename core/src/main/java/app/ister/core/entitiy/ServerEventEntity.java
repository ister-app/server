package app.ister.core.entitiy;

import app.ister.core.enums.EventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.*;
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
    @Builder.Default
    private Boolean failed = false;

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
