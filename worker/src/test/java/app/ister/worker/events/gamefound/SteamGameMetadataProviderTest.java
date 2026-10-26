package app.ister.worker.events.gamefound;

import app.ister.core.entity.GameEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.GameForm;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.MetadataSource;
import app.ister.core.repository.ImageRepository;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SteamGameMetadataProviderTest {

    private static final String CDN = "https://cdn.example/steam/apps";

    @Mock
    private ImageRepository imageRepository;
    @Mock
    private ImageDownloadService imageDownloadService;

    private SteamGameMetadataProvider subject;

    @BeforeEach
    void setUp() {
        subject = new SteamGameMetadataProvider(imageRepository, imageDownloadService, CDN, true);
    }

    private static GameEntity steamGame(Long appId) {
        GameEntity game = GameEntity.builder().name("LEGO Harry Potter Collection").form(GameForm.STEAM).steamAppId(appId).build();
        game.setId(UUID.randomUUID());
        return game;
    }

    @Test
    void aSteamGameWithoutArtworkGetsTheLibraryCapsuleAndHero() throws IOException {
        GameEntity game = steamGame(2950340L);
        when(imageRepository.findByGameEntityId(game.getId())).thenReturn(List.of());

        assertFalse(subject.enrich(game), "images arrive through IMAGE_FOUND, the entity is unchanged");

        String cover = CDN + "/2950340/library_600x900.jpg";
        String hero = CDN + "/2950340/library_hero.jpg";
        verify(imageDownloadService).downloadAndSave(eq(cover), eq(ImageType.COVER), isNull(), eq("steam://" + cover),
                argThat(ref -> ref.game() == game), eq(Duration.ofSeconds(15)));
        verify(imageDownloadService).downloadAndSave(eq(hero), eq(ImageType.BACKGROUND), isNull(), eq("steam://" + hero),
                argThat(ref -> ref.game() == game), eq(Duration.ofSeconds(15)));
        assertEquals(MetadataSource.STEAM, MetadataSource.fromSourceUri("steam://" + cover).orElseThrow());
    }

    @Test
    void existingArtworkWins() {
        GameEntity game = steamGame(620L);
        when(imageRepository.findByGameEntityId(game.getId())).thenReturn(List.of(new ImageEntity()));

        subject.enrich(game);

        verifyNoInteractions(imageDownloadService);
    }

    @Test
    void achievementIconsAreNotArtwork() throws IOException {
        GameEntity game = steamGame(2950340L);
        when(imageRepository.findByGameEntityId(game.getId()))
                .thenReturn(List.of(ImageEntity.builder().type(ImageType.ICON).sourceUri("achievement://x/STEAM/ACH_1").build()));

        subject.enrich(game);

        verify(imageDownloadService).downloadAndSave(eq(CDN + "/2950340/library_600x900.jpg"), eq(ImageType.COVER), any(), any(), any(), any());
    }

    @Test
    void gamesWithoutAnAppIdAreLeftAlone() {
        subject.enrich(steamGame(null));
        verifyNoInteractions(imageRepository, imageDownloadService);
    }

    @Test
    void disabledFetchesNothing() {
        new SteamGameMetadataProvider(imageRepository, imageDownloadService, CDN, false).enrich(steamGame(620L));
        verifyNoInteractions(imageRepository, imageDownloadService);
    }

    @Test
    void aMissingCoverDoesNotStopTheBackground() throws IOException {
        GameEntity game = steamGame(620L);
        when(imageRepository.findByGameEntityId(game.getId())).thenReturn(List.of());
        doThrow(new IOException("HTTP 404")).when(imageDownloadService)
                .downloadAndSave(eq(CDN + "/620/library_600x900.jpg"), any(), any(), any(), any(), any());

        subject.enrich(game);

        verify(imageDownloadService).downloadAndSave(eq(CDN + "/620/library_hero.jpg"), eq(ImageType.BACKGROUND), any(), any(), any(), any());
    }

    @Test
    void insideATransactionTheDownloadsWaitForTheCommit() throws IOException {
        GameEntity game = steamGame(620L);
        when(imageRepository.findByGameEntityId(game.getId())).thenReturn(List.of());
        TransactionSynchronizationManager.initSynchronization();
        try {
            subject.enrich(game);
            verifyNoInteractions(imageDownloadService);

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(imageDownloadService).downloadAndSave(eq(CDN + "/620/library_600x900.jpg"), eq(ImageType.COVER), any(), any(), any(), any());
            verify(imageDownloadService).downloadAndSave(eq(CDN + "/620/library_hero.jpg"), eq(ImageType.BACKGROUND), any(), any(), any(), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aRuntimeFailureOfTheCacheStoreIsSwallowed() throws IOException {
        GameEntity game = steamGame(620L);
        when(imageRepository.findByGameEntityId(game.getId())).thenReturn(List.of());
        doThrow(new IllegalStateException("no cache directory")).when(imageDownloadService)
                .downloadAndSave(any(), any(), any(), any(), any(), any());

        subject.enrich(game);

        verify(imageDownloadService).downloadAndSave(eq(CDN + "/620/library_hero.jpg"), eq(ImageType.BACKGROUND), any(), any(), any(), any());
    }
}
