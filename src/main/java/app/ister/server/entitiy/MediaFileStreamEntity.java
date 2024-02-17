package app.ister.server.entitiy;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.*;

@Entity
@RequiredArgsConstructor(access = AccessLevel.PUBLIC)
@NoArgsConstructor
@Data
public class MediaFileStreamEntity extends BaseEntity {

    @NonNull
    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private MediaFileEntity mediaFileEntity;

    @NonNull
    private int index;

    @NonNull
    private String codecName;

    @NonNull
    private String codecType;

    @NonNull
    private int width;

    @NonNull
    private int height;

    private String language;

    private String title;
}
