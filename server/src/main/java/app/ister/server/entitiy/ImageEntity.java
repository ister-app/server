package app.ister.server.entitiy;

import app.ister.server.enums.ImageType;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"directoryEntityId", "path"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class ImageEntity extends BaseEntity {

    @ManyToOne(optional=false)
    private DirectoryEntity directoryEntity;

    @Column(nullable = false)
    private String path;

    @Column(nullable = false)
    private ImageType type;

    // https://en.wikipedia.org/wiki/ISO_639-3
    private String language;

    private String sourceUri;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private ShowEntity showEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private SeasonEntity seasonEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private EpisodeEntity episodeEntity;

}
