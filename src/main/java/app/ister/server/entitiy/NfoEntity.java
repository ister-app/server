package app.ister.server.entitiy;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class NfoEntity extends BaseEntity {
    @NotNull
    @ManyToOne
    private DiskEntity diskEntity;

    @NotNull
    private String path;
}
