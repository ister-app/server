package app.ister.core.entity;

import app.ister.core.enums.RemoteControlScope;
import app.ister.core.enums.SharingScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A user's playback-session sharing preferences. At most one row per user (unique index in Flyway);
 * a user without a row falls back to the defaults: now-playing {@link SharingScope#EVERYONE} and
 * remote control {@link RemoteControlScope#PRIVATE}. The allowlists themselves live in
 * {@link UserSharingGrantEntity}.
 */
@Entity
@Table(name = "user_sharing_settings")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UserSharingSettingsEntity extends BaseEntity {

    @OneToOne(optional = false)
    private UserEntity userEntity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SharingScope nowPlayingScope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RemoteControlScope controlScope;
}
