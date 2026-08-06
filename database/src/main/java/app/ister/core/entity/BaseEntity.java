package app.ister.core.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
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
@Getter
@Setter
@ToString
@SuperBuilder
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {
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

    // instanceof instead of getClass(): a Hibernate proxy must compare equal to its entity.
    // Not final: a lazy proxy overrides these to delegate to the initialized entity.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BaseEntity that)) return false;
        return id != null && id.equals(that.getId());
    }

    // Constant per class: the id is only assigned on insert, and a hash that changes
    // mid-lifecycle corrupts hash-based collections.
    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
