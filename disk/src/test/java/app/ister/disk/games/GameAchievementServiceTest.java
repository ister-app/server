package app.ister.disk.games;

import app.ister.core.config.GameProperties;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.GameAchievementEntity;
import app.ister.core.entity.GameEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserGameAchievementEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.GameAchievementSource;
import app.ister.core.enums.ImageType;
import app.ister.core.repository.GameAchievementRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.UserGameAchievementRepository;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.disk.storage.CacheStoreMocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A client's achievement report: definitions are upserted, unlock times never move forwards. */
class GameAchievementServiceTest {

    private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-02T10:00:00Z");

    private final GameAchievementRepository achievementRepository = mock(GameAchievementRepository.class);
    private final UserGameAchievementRepository userAchievementRepository = mock(UserGameAchievementRepository.class);
    private final ImageRepository imageRepository = mock(ImageRepository.class);
    private final CacheDirectoryResolver resolver = mock(CacheDirectoryResolver.class);
    private final GameProperties properties = new GameProperties();
    private final UserEntity user = UserEntity.builder().name("alice").build();
    private final GameEntity game = GameEntity.builder().name("Chrono").build();
    private final DirectoryEntity cacheDir = DirectoryEntity.builder().name("cache").path("/cache")
            .directoryType(DirectoryType.CACHE).build();
    private GameAchievementService service;

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        game.setId(UUID.randomUUID());
        cacheDir.setId(UUID.randomUUID());
        when(resolver.forThisNode()).thenReturn(cacheDir);
        CacheStoreMocks.fake(resolver);
        when(achievementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userAchievementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(imageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new GameAchievementService(achievementRepository, userAchievementRepository, imageRepository,
                resolver, properties);
    }

    private static GameAchievementService.Reported reported(String id, String name, Instant unlockedAt) {
        return new GameAchievementService.Reported(id, name, "desc " + id, "BRONZE", false, 1, unlockedAt);
    }

    private GameAchievementEntity definition(String externalId, GameAchievementSource source) {
        GameAchievementEntity definition = GameAchievementEntity.builder().gameEntity(game).source(source)
                .externalId(externalId).name("Name " + externalId).description("desc " + externalId)
                .grade("BRONZE").hidden(false).sortOrder(1).build();
        definition.setId(UUID.randomUUID());
        return definition;
    }

    @Test
    void aFirstReportCreatesDefinitionsAndUnlocks() {
        when(achievementRepository.findByGameEntityIdAndSource(game.getId(), GameAchievementSource.PS3_TROPHY)).thenReturn(List.of());
        when(userAchievementRepository.findByUserAndGame(user.getId(), game.getId()))
                .thenReturn(List.of())
                .thenReturn(List.of(mock(UserGameAchievementEntity.class)));
        when(achievementRepository.countByGameEntityId(game.getId())).thenReturn(2L);

        GameAchievementService.Summary summary = service.report(user, game, GameAchievementSource.PS3_TROPHY,
                List.of(reported("t1", "First", T1), reported("t2", "Second", null)), "desk");

        assertThat(summary).isEqualTo(new GameAchievementService.Summary(2, 1));
        ArgumentCaptor<GameAchievementEntity> definitions = ArgumentCaptor.forClass(GameAchievementEntity.class);
        verify(achievementRepository, times(2)).save(definitions.capture());
        assertThat(definitions.getAllValues()).extracting(GameAchievementEntity::getExternalId).containsExactly("t1", "t2");
        assertThat(definitions.getAllValues().getFirst().getGameEntity()).isSameAs(game);
        assertThat(definitions.getAllValues().getFirst().getSource()).isEqualTo(GameAchievementSource.PS3_TROPHY);
        ArgumentCaptor<UserGameAchievementEntity> unlocks = ArgumentCaptor.forClass(UserGameAchievementEntity.class);
        verify(userAchievementRepository).save(unlocks.capture());
        assertThat(unlocks.getValue().getGameAchievementEntity().getExternalId()).isEqualTo("t1");
        assertThat(unlocks.getValue().getUnlockedAt()).isEqualTo(T1);
        assertThat(unlocks.getValue().getDeviceName()).isEqualTo("desk");
        assertThat(unlocks.getValue().getUserEntity()).isSameAs(user);
    }

    @Test
    void itemsWithoutIdOrNameAreSkipped() {
        when(achievementRepository.findByGameEntityIdAndSource(any(), any())).thenReturn(List.of());
        when(userAchievementRepository.findByUserAndGame(any(), any())).thenReturn(List.of());

        service.report(user, game, GameAchievementSource.STEAM, List.of(
                reported("", "Blank id", T1),
                reported(null, "Null id", T1),
                reported("ok", null, T1)), null);

        verify(achievementRepository, never()).save(any());
        verify(userAchievementRepository, never()).save(any());
    }

    @Test
    void aChangedDefinitionIsUpdatedAnUnchangedOneIsNotSavedAgain() {
        GameAchievementEntity same = definition("same", GameAchievementSource.STEAM);
        GameAchievementEntity changed = definition("changed", GameAchievementSource.STEAM);
        when(achievementRepository.findByGameEntityIdAndSource(game.getId(), GameAchievementSource.STEAM))
                .thenReturn(List.of(same, changed));
        when(userAchievementRepository.findByUserAndGame(any(), any())).thenReturn(List.of());

        service.report(user, game, GameAchievementSource.STEAM, List.of(
                new GameAchievementService.Reported("same", "Name same", "desc same", "BRONZE", false, 1, null),
                new GameAchievementService.Reported("changed", "New name", "new desc", "GOLD", true, 5, null)), null);

        verify(achievementRepository, never()).save(same);
        verify(achievementRepository).save(changed);
        assertThat(changed.getName()).isEqualTo("New name");
        assertThat(changed.getDescription()).isEqualTo("new desc");
        assertThat(changed.getGrade()).isEqualTo("GOLD");
        assertThat(changed.isHidden()).isTrue();
        assertThat(changed.getSortOrder()).isEqualTo(5);
    }

    @Test
    void anEarlierUnlockTimeReplacesTheRecordedOneALaterOneDoesNot() {
        GameAchievementEntity earlier = definition("earlier", GameAchievementSource.PS3_TROPHY);
        GameAchievementEntity later = definition("later", GameAchievementSource.PS3_TROPHY);
        GameAchievementEntity otherSource = definition("other", GameAchievementSource.STEAM);
        when(achievementRepository.findByGameEntityIdAndSource(game.getId(), GameAchievementSource.PS3_TROPHY))
                .thenReturn(List.of(earlier, later));
        UserGameAchievementEntity earlierUnlock = UserGameAchievementEntity.builder().userEntity(user)
                .gameAchievementEntity(earlier).unlockedAt(T2).build();
        UserGameAchievementEntity laterUnlock = UserGameAchievementEntity.builder().userEntity(user)
                .gameAchievementEntity(later).unlockedAt(T1).build();
        UserGameAchievementEntity otherUnlock = UserGameAchievementEntity.builder().userEntity(user)
                .gameAchievementEntity(otherSource).unlockedAt(T1).build();
        when(userAchievementRepository.findByUserAndGame(user.getId(), game.getId()))
                .thenReturn(List.of(earlierUnlock, laterUnlock, otherUnlock));

        service.report(user, game, GameAchievementSource.PS3_TROPHY, List.of(
                new GameAchievementService.Reported("earlier", "Name earlier", "desc earlier", "BRONZE", false, 1, T1),
                new GameAchievementService.Reported("later", "Name later", "desc later", "BRONZE", false, 1, T2)), null);

        assertThat(earlierUnlock.getUnlockedAt()).isEqualTo(T1);
        assertThat(laterUnlock.getUnlockedAt()).as("never moves forwards").isEqualTo(T1);
        verify(userAchievementRepository).save(earlierUnlock);
        verify(userAchievementRepository, never()).save(laterUnlock);
        verify(achievementRepository, never()).save(any());
    }

    @Test
    void storeIconRejectsAnOversizedPng() {
        properties.setAchievementIconMaxBytes(DataSize.ofBytes(2));

        assertThatThrownBy(() -> service.storeIcon(game, GameAchievementSource.STEAM, "a", new byte[3]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Icon larger than");
        verify(imageRepository, never()).save(any());
    }

    @Test
    void storeIconIsEmptyForAnUnknownDefinition() throws Exception {
        when(achievementRepository.findByGameEntityIdAndSource(game.getId(), GameAchievementSource.STEAM))
                .thenReturn(List.of(definition("other", GameAchievementSource.STEAM)));

        assertThat(service.storeIcon(game, GameAchievementSource.STEAM, "missing", new byte[]{1})).isEmpty();
        verify(imageRepository, never()).save(any());
    }

    @Test
    void storeIconWritesAnIconImageAndLinksTheDefinition() throws Exception {
        GameAchievementEntity definition = definition("ACH_1/odd id", GameAchievementSource.STEAM);
        when(achievementRepository.findByGameEntityIdAndSource(game.getId(), GameAchievementSource.STEAM))
                .thenReturn(List.of(definition));
        when(imageRepository.findByDirectoryEntityIdAndPath(any(), any())).thenReturn(Optional.empty());

        Optional<ImageEntity> image = service.storeIcon(game, GameAchievementSource.STEAM, "ACH_1/odd id", new byte[]{1, 2});

        assertThat(image).isPresent();
        assertThat(image.get().getPath()).isEqualTo("/cache/achievements/" + game.getId() + "/steam/ACH_1_odd_id.png");
        assertThat(image.get().getType()).isEqualTo(ImageType.ICON);
        assertThat(image.get().getDirectoryEntityId()).isEqualTo(cacheDir.getId());
        assertThat(image.get().getGameEntityId()).isEqualTo(game.getId());
        assertThat(image.get().getSourceUri()).isEqualTo("achievement://" + game.getId() + "/STEAM/ACH_1/odd id");
        assertThat(definition.getIconImageEntity()).isSameAs(image.get());
        verify(imageRepository).save(image.get());
        verify(achievementRepository).save(definition);
    }

    @Test
    void aSecondIconUploadReusesTheExistingImageRow() throws Exception {
        GameAchievementEntity definition = definition("t1", GameAchievementSource.PS3_TROPHY);
        when(achievementRepository.findByGameEntityIdAndSource(game.getId(), GameAchievementSource.PS3_TROPHY))
                .thenReturn(List.of(definition));
        ImageEntity existing = ImageEntity.builder().directoryEntityId(cacheDir.getId())
                .path("/cache/achievements/" + game.getId() + "/ps3_trophy/t1.png").type(ImageType.ICON).build();
        existing.setId(UUID.randomUUID());
        when(imageRepository.findByDirectoryEntityIdAndPath(cacheDir.getId(), existing.getPath())).thenReturn(Optional.of(existing));

        Optional<ImageEntity> image = service.storeIcon(game, GameAchievementSource.PS3_TROPHY, "t1", new byte[]{1});

        assertThat(image).contains(existing);
        assertThat(existing.getGameEntityId()).isEqualTo(game.getId());
        assertThat(existing.getFileLastModifiedTime()).isNotNull();
        verify(imageRepository).save(existing);
    }
}
