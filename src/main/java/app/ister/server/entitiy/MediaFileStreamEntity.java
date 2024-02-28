package app.ister.server.entitiy;

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
public class MediaFileStreamEntity extends BaseEntity {

    @NotNull
    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private MediaFileEntity mediaFileEntity;

    @NotNull
    private int streamIndex;

    @NotNull
    private String codecName;

    @NotNull
    private String codecType;

    @NotNull
    private int width;

    @NotNull
    private int height;

    private String language;

    private String title;
}
