package app.ister.server.entitiy;

import app.ister.server.enums.DiskType;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class DiskEntity extends BaseEntity {

    @NotNull
    @ManyToOne
    private NodeEntity nodeEntity;

    @NotNull
    @ManyToOne
    private CategorieEntity categorieEntity;

    @NotNull
    private String path;

    @NotNull
    private DiskType diskType;

}
