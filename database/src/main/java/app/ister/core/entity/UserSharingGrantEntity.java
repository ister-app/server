package app.ister.core.entity;

import app.ister.core.enums.SharingCapability;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Grants one user (the grantee) a {@link SharingCapability} over the owner's playback sessions:
 * VIEW to see them in now-playing, CONTROL to remote-control them. At most one row per
 * (owner, grantee, capability) — enforced by a unique index in Flyway.
 */
@Entity
@Table(name = "user_sharing_grant")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UserSharingGrantEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private UserEntity ownerEntity;

    @ManyToOne(optional = false)
    private UserEntity granteeEntity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SharingCapability capability;
}
