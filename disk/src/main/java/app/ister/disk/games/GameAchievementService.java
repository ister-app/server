package app.ister.disk.games;

import app.ister.core.config.GameProperties;
import app.ister.core.entity.GameAchievementEntity;
import app.ister.core.entity.GameEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserGameAchievementEntity;
import app.ister.core.enums.GameAchievementSource;
import app.ister.core.enums.ImageType;
import app.ister.core.repository.GameAchievementRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.UserGameAchievementRepository;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.storage.CacheStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Achievements as reported by the client, which reads them from the local emulator/store files
 * after a session. A report carries the game's full definition list (so "7 of 42" is right) plus
 * the unlocks it saw; definitions are upserted per (game, source, externalId), an unlock's
 * timestamp never moves backwards. Icons arrive separately as PNG bodies and are stored as
 * {@link ImageType#ICON} images in the cache store, which also keeps the cache cleanup from
 * sweeping them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameAchievementService {

    /**
     * One achievement as the client saw it. {@code unlockedAt} is null while locked. {@code hidden}
     * and {@code sortOrder} are boxed so a client may leave them out of the JSON (Jackson rejects
     * a missing primitive); they are normalized to {@code false} / {@code 0} and never null.
     */
    public record Reported(String externalId, String name, @Nullable String description, @Nullable String grade,
                           Boolean hidden, Integer sortOrder, @Nullable Instant unlockedAt) {
        public Reported {
            hidden = Boolean.TRUE.equals(hidden);
            sortOrder = sortOrder == null ? 0 : sortOrder;
        }
    }

    public record Summary(int total, int unlocked) {}

    private final GameAchievementRepository achievementRepository;
    private final UserGameAchievementRepository userAchievementRepository;
    private final ImageRepository imageRepository;
    private final CacheDirectoryResolver cacheDirectoryResolver;
    private final GameProperties properties;

    /**
     * Records a report. With {@code complete} the client vouches that {@code reported} is the game's
     * whole set for {@code source} (a trophy config, a Steam schema): definitions of that source the
     * report does not name are stale — a set credited to the wrong game by an earlier client — and
     * are removed with every user's unlocks of them. A partial or empty report never removes anything.
     */
    @Transactional
    public Summary report(UserEntity user, GameEntity game, GameAchievementSource source,
                          List<Reported> reported, @Nullable String deviceName, boolean complete) {
        Map<String, GameAchievementEntity> existing = new HashMap<>();
        achievementRepository.findByGameEntityIdAndSource(game.getId(), source)
                .forEach(a -> existing.put(a.getExternalId(), a));
        Map<String, UserGameAchievementEntity> unlocks = new HashMap<>();
        userAchievementRepository.findByUserAndGame(user.getId(), game.getId()).stream()
                .filter(u -> u.getGameAchievementEntity().getSource() == source)
                .forEach(u -> unlocks.put(u.getGameAchievementEntity().getExternalId(), u));

        for (Reported item : reported) {
            if (item.externalId() == null || item.externalId().isBlank() || item.name() == null) {
                continue;
            }
            GameAchievementEntity definition = upsertDefinition(existing, game, source, item);
            if (item.unlockedAt() != null) {
                upsertUnlock(unlocks, user, definition, item.unlockedAt(), deviceName);
            }
        }
        if (complete && !reported.isEmpty()) {
            Set<String> current = reported.stream().map(Reported::externalId).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            List<GameAchievementEntity> stale = existing.values().stream()
                    .filter(a -> !current.contains(a.getExternalId())).toList();
            if (!stale.isEmpty()) {
                log.info("Dropping {} {} achievement(s) of game {} the client's complete set does not carry",
                        stale.size(), source, game.getId());
                userAchievementRepository.deleteByDefinitions(stale);
                achievementRepository.deleteAll(stale);
            }
        }
        int unlocked = userAchievementRepository.findByUserAndGame(user.getId(), game.getId()).size();
        return new Summary((int) achievementRepository.countByGameEntityId(game.getId()), unlocked);
    }

    private GameAchievementEntity upsertDefinition(Map<String, GameAchievementEntity> existing, GameEntity game,
                                                   GameAchievementSource source, Reported item) {
        GameAchievementEntity definition = existing.get(item.externalId());
        if (definition == null) {
            definition = achievementRepository.save(GameAchievementEntity.builder()
                    .gameEntity(game)
                    .source(source)
                    .externalId(item.externalId())
                    .name(item.name())
                    .description(item.description())
                    .grade(item.grade())
                    .hidden(item.hidden())
                    .sortOrder(item.sortOrder())
                    .build());
            existing.put(item.externalId(), definition);
            return definition;
        }
        boolean changed = !Objects.equals(definition.getName(), item.name())
                || !Objects.equals(definition.getDescription(), item.description())
                || !Objects.equals(definition.getGrade(), item.grade())
                || definition.isHidden() != item.hidden()
                || definition.getSortOrder() != item.sortOrder();
        if (changed) {
            definition.setName(item.name());
            definition.setDescription(item.description());
            definition.setGrade(item.grade());
            definition.setHidden(item.hidden());
            definition.setSortOrder(item.sortOrder());
            achievementRepository.save(definition);
        }
        return definition;
    }

    private void upsertUnlock(Map<String, UserGameAchievementEntity> unlocks, UserEntity user,
                              GameAchievementEntity definition, Instant unlockedAt, @Nullable String deviceName) {
        UserGameAchievementEntity unlock = unlocks.get(definition.getExternalId());
        if (unlock == null) {
            unlocks.put(definition.getExternalId(), userAchievementRepository.save(UserGameAchievementEntity.builder()
                    .userEntity(user)
                    .gameAchievementEntity(definition)
                    .unlockedAt(unlockedAt)
                    .deviceName(deviceName)
                    .build()));
        } else if (unlockedAt.isBefore(unlock.getUnlockedAt())) {
            // An earlier, more precise timestamp (e.g. the trophy file's) replaces a "first seen" one.
            unlock.setUnlockedAt(unlockedAt);
            userAchievementRepository.save(unlock);
        }
    }

    /** Stores an icon for a definition; a second upload replaces the reference, the old file is swept later. */
    @Transactional(rollbackFor = IOException.class)
    public Optional<ImageEntity> storeIcon(GameEntity game, GameAchievementSource source, String externalId,
                                           byte[] png) throws IOException {
        if (png.length > properties.getAchievementIconMaxBytes().toBytes()) {
            throw new IllegalArgumentException("Icon larger than " + properties.getAchievementIconMaxBytes());
        }
        Optional<GameAchievementEntity> definition = achievementRepository
                .findByGameEntityIdAndSource(game.getId(), source).stream()
                .filter(a -> a.getExternalId().equals(externalId))
                .findFirst();
        if (definition.isEmpty()) {
            return Optional.empty();
        }
        CacheStore store = cacheDirectoryResolver.store();
        String key = "achievements/" + game.getId() + "/" + source.name().toLowerCase() + "/"
                + externalId.replaceAll("[^A-Za-z0-9._-]", "_") + ".png";
        String storedPath = store.write(key, png, "image/png");
        ImageEntity image = imageRepository.findByDirectoryEntityIdAndPath(store.directory().getId(), storedPath)
                .orElseGet(() -> ImageEntity.builder()
                        .directoryEntityId(store.directory().getId())
                        .path(storedPath)
                        .type(ImageType.ICON)
                        .sourceUri("achievement://" + game.getId() + "/" + source + "/" + externalId)
                        .fileCreationTime(Instant.now())
                        .fileLastModifiedTime(Instant.now())
                        .build());
        image.setGameEntityId(game.getId());
        image.setFileLastModifiedTime(Instant.now());
        imageRepository.save(image);
        definition.get().setIconImageEntity(image);
        achievementRepository.save(definition.get());
        return Optional.of(image);
    }
}
