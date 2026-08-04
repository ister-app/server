package app.ister.core.entity;

import app.ister.core.enums.DevicePlatform;
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

import java.time.Instant;
import java.util.UUID;

/**
 * A registered client device (one row per app install per user). {@code deviceId} is the
 * client-generated install UUID; it is only unique per user (Flyway unique index), so callers
 * must always look devices up scoped by owner.
 */
@Entity
@Table(name = "device_entity")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class DeviceEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private UserEntity userEntity;

    @Column(nullable = false)
    private UUID deviceId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DevicePlatform platform;

    @Column(nullable = false)
    private Instant lastSeenAt;
}
