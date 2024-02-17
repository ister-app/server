package app.ister.server.entitiy;

import app.ister.server.enums.DiskType;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Entity
@RequiredArgsConstructor
@NoArgsConstructor
@Data
public class DiskEntity extends BaseEntity {

    @NonNull
    @ManyToOne
    private NodeEntity nodeEntity;

    @NonNull
    @ManyToOne
    private CategorieEntity categorieEntity;

    @NonNull
    private String path;

    @NonNull
    private DiskType diskType;

}
