package app.ister.core.entity;

import app.ister.core.enums.PathFileType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"directoryEntityId", "path"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class OtherPathFileEntity extends FileFromPathEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PathFileType pathFileType;

    @Setter
    @ManyToOne(optional = true)
    private MetadataEntity metadataEntity;

    @Setter
    @ManyToOne(optional = true)
    private MediaFileStreamEntity mediaFileStreamEntity;
}
