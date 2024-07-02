package app.ister.server.entitiy;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * This class is extended by the other entities so al entities hava a:
 * -
 */
@EqualsAndHashCode(callSuper = true)
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PACKAGE)
@Data
@SuperBuilder
@EntityListeners(AuditingEntityListener.class)
public class FileFromPathEntity extends BaseEntity {
    @ManyToOne(optional = false)
    private DirectoryEntity directoryEntity;

    @Column(nullable = false)
    private String path;
}
