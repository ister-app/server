package app.ister.core.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * One save-game snapshot of a user for a game: a zip the client uploaded after a play session,
 * stored in the cache store under {@code saves/<userId>/<gameId>/<version>.zip}. Versions count
 * up per (user, game); the newest N survive (app.ister.games.save-retention). A restore of an
 * older version is written as a new head version so the history stays linear.
 */
@Entity
@Table(name = "game_save_entity", uniqueConstraints = @UniqueConstraint(
        name = "game_save_entity_version_uq", columnNames = {"userEntityId", "gameEntityId", "version"}))
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class GameSaveEntity extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private UserEntity userEntity;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private GameEntity gameEntity;

    @Column(nullable = false)
    private int version;

    /** The cache directory holding the zip (node-local cache or the shared S3 cache). */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private DirectoryEntity cacheDirectoryEntity;

    /** Full stored path ({@code directory.path + "/" + key}), what the cache cleanup checks against. */
    @Column(nullable = false, columnDefinition = "text")
    private String storedPath;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    /** The client that uploaded it (hostname), for the conflict dialog. */
    private String deviceName;

    /** Free label, e.g. "restored from v3". */
    private String note;
}
