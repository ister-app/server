package app.ister.server.entitiy;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.sql.Timestamp;

@Entity
@SuperBuilder
//@RequiredArgsConstructor(access = AccessLevel.PUBLIC)
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@Getter
public class MetadataEntity extends BaseEntity {
    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private ShowEntity showEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private SeasonEntity seasonEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private EpisodeEntity episodeEntity;

    private String sourceUri;

    private String title;
    @Column(columnDefinition = "text")
    private String description;
    private Timestamp released;
}
