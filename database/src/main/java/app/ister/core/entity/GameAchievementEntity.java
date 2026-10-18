package app.ister.core.entity;

import app.ister.core.enums.GameAchievementSource;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * An achievement definition of a game (a PS3 trophy, a Steam achievement), reported by the client
 * from the local emulator/store files together with the full list, so "7 of 42" can be computed.
 * Unlocks per user live in {@link UserGameAchievementEntity}.
 */
@Entity
@Table(name = "game_achievement_entity", uniqueConstraints = @UniqueConstraint(
        name = "game_achievement_entity_identity", columnNames = {"gameEntityId", "source", "externalId"}))
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class GameAchievementEntity extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private GameEntity gameEntity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private GameAchievementSource source;

    /** Trophy id or Steam api name: stable within (game, source). */
    @Column(nullable = false, length = 128)
    private String externalId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    /** PS3: BRONZE / SILVER / GOLD / PLATINUM; null for sources without grades. */
    @Column(length = 16)
    private String grade;

    @Column(nullable = false)
    private boolean hidden;

    @Column(nullable = false)
    private int sortOrder;

    /** The icon, stored as an {@link app.ister.core.enums.ImageType#ICON} image in the cache store. */
    @ManyToOne(fetch = FetchType.LAZY)
    private ImageEntity iconImageEntity;
}
