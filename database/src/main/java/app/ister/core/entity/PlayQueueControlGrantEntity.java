package app.ister.core.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Per-session remote-control grant: one grantee allowed to control one play queue. Used only when
 * the queue's {@code controlScopeOverride} is {@code ALLOWLIST} — a list that is deliberately
 * independent of the owner's account-level {@link UserSharingGrantEntity} CONTROL grants. At most
 * one row per (play queue, grantee), enforced by a unique index in Flyway.
 */
@Entity
@Table(name = "play_queue_control_grant")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class PlayQueueControlGrantEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private PlayQueueEntity playQueueEntity;

    @ManyToOne(optional = false)
    private UserEntity granteeEntity;
}
