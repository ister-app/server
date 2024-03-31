package app.ister.server.entitiy;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
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
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(nullable = false)
    private UUID id;

    @Column(nullable = false)
    @CreatedDate
    Instant dateCreated;

    @Column(nullable = false)
    @LastModifiedDate
    Instant dateUpdated;
}
