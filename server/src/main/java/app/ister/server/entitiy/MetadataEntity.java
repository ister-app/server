package app.ister.server.entitiy;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class MetadataEntity extends BaseEntity {
    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private MovieEntity movieEntity;

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

    // https://en.wikipedia.org/wiki/ISO_639-3
    private String language;

    private String title;
    @Column(columnDefinition = "text")
    private String description;
    private LocalDate released;
}
