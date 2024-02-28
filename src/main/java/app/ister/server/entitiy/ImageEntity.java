package app.ister.server.entitiy;

import app.ister.server.enums.ImageType;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class ImageEntity extends BaseEntity {

    @NotNull
    @ManyToOne
    private DiskEntity diskEntity;

    @NotNull
    private String path;

    @NotNull
    private ImageType type;

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
