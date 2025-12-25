package app.ister.core.entitiy;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * This class is extended by the other entities so al entities hava a UUID and the correct date time instants.
 */
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PACKAGE)
@Data
@SuperBuilder
@EntityListeners(AuditingEntityListener.class)
public class BaseEntity {
    @Column(nullable = false)
    @CreatedDate
    Instant dateCreated;
    @Column(nullable = false)
    @LastModifiedDate
    Instant dateUpdated;
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(nullable = false)
    private UUID id;
}
