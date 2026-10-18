package app.ister.core.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/** A user unlocked an achievement. {@code unlockedAt} never moves backwards once recorded. */
@Entity
@Table(name = "user_game_achievement_entity", uniqueConstraints = @UniqueConstraint(
        name = "user_game_achievement_entity_uq", columnNames = {"userEntityId", "gameAchievementEntityId"}))
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UserGameAchievementEntity extends BaseEntity {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private UserEntity userEntity;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private GameAchievementEntity gameAchievementEntity;

    @Column(nullable = false)
    private Instant unlockedAt;

    private String deviceName;
}
