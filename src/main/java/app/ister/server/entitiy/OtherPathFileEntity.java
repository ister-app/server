package app.ister.server.entitiy;

import app.ister.server.enums.PathFileType;
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
public class OtherPathFileEntity extends BaseEntity {

    @ManyToOne(optional=false)
    private DirectoryEntity directoryEntity;

    @Column(nullable = false)
    private PathFileType pathFileType;

    @Column(nullable = false)
    private String path;
}
