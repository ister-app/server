package app.ister.server.entitiy;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@RequiredArgsConstructor(access = AccessLevel.PUBLIC)
@NoArgsConstructor
@Data
public class ShowEntity extends BaseEntity {

    @NonNull
    @ManyToOne
    private CategorieEntity categorieEntity;

    @NonNull
    private String name;

    @NonNull
    private int releaseYear;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "showEntity")
    private List<ImageEntity> imageEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "showEntity")
    private List<MetadataEntity> metadataEntities;
}
