package app.ister.api.controller;

import app.ister.core.entity.PlayQueueEntity;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PlayQueueControlGrantRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.PlayQueuePrefetchService;
import app.ister.core.service.PlayQueueService;
import app.ister.core.status.PlaybackCommandService;
import app.ister.core.status.PlaybackSessionRegistry;
import app.ister.core.status.PlaybackStatusService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Schema-wiring test for getPlayQueue: the queue's own resume state
 * (currentItemId + progressInMilliseconds) must round-trip to the client.
 */
@GraphQlTest(PlayQueueController.class)
class PlayQueueControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private PlayQueueService playQueueService;

    @MockitoBean
    private EpisodeRepository episodeRepository;

    @MockitoBean
    private MovieRepository movieRepository;

    @MockitoBean
    private TrackRepository trackRepository;

    @MockitoBean
    private ChapterRepository chapterRepository;

    @MockitoBean
    private PodcastEpisodeRepository podcastEpisodeRepository;

    @MockitoBean
    private MediaFileRepository mediaFileRepository;

    @MockitoBean
    private app.ister.core.service.MediaFileEpisodeService mediaFileEpisodeService;

    @MockitoBean
    private ImageRepository imageRepository;

    @MockitoBean
    private PlayQueuePrefetchService playQueuePrefetchService;

    @MockitoBean
    private PlaybackStatusService playbackStatusService;

    @MockitoBean
    private PlaybackSessionRegistry playbackSessionRegistry;

    @MockitoBean
    private PlaybackCommandService playbackCommandService;

    @MockitoBean
    private PlayQueueControlGrantRepository playQueueControlGrantRepository;

    @MockitoBean
    private app.ister.core.status.FollowerRegistry followerRegistry;

    @MockitoBean
    private app.ister.core.service.LibraryAccessService libraryAccessService;

    @MockitoBean
    private app.ister.core.service.MediaLibraryResolver mediaLibraryResolver;

    @MockitoBean
    private app.ister.core.service.DeviceService deviceService;

    @BeforeEach
    void authenticateAsUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "test-user", null,
                        List.of(new SimpleGrantedAuthority("ROLE_user"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getPlayQueueExposesCurrentItemAndProgress() {
        UUID currentItemId = UUID.randomUUID();
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .currentItem(currentItemId)
                .progressInMilliseconds(42_000)
                .build();
        queue.setId(UUID.randomUUID());
        when(playQueueService.getPlayQueue(eq(queue.getId()), any())).thenReturn(Optional.of(queue));

        assertDoesNotThrow(() -> graphQlTester.document("""
                        { getPlayQueue(id: "%s") { id currentItemId progressInMilliseconds } }
                        """.formatted(queue.getId()))
                .execute()
                .path("getPlayQueue.currentItemId").entity(String.class).isEqualTo(currentItemId.toString())
                .path("getPlayQueue.progressInMilliseconds").entity(Integer.class).isEqualTo(42_000));
    }

    /**
     * The updatePlayQueue arguments are bound as one object off the argument map
     * (@Arguments record); this executes the real mutation so that binding — including
     * the absent optional arguments — is actually exercised.
     */
    @Test
    void updatePlayQueueBindsArgumentsOffTheArgumentMap() {
        UUID id = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .userEntity(app.ister.core.entity.UserEntity.builder()
                        .externalId("test-user").name("Test user").build())
                .progressInMilliseconds(5_000)
                .build();
        queue.setId(id);
        when(playQueueService.updatePlayQueue(eq(id), eq(5_000L), eq(itemId), any(), any(), any()))
                .thenReturn(Optional.of(queue));

        assertDoesNotThrow(() -> graphQlTester.document("""
                        mutation { updatePlayQueue(id: "%s", progressInMilliseconds: 5000, playQueueItemId: "%s") { id } }
                        """.formatted(id, itemId))
                .execute()
                .path("updatePlayQueue.id").entity(String.class).isEqualTo(id.toString()));
    }

    @Test
    void playQueueItemsExposeThePerViewerAccessibleFlag() {
        app.ister.core.entity.PlayQueueItemEntity item = app.ister.core.entity.PlayQueueItemEntity.builder()
                .type(app.ister.core.enums.MediaType.TRACK)
                .position(java.math.BigDecimal.ONE)
                .build();
        item.setId(UUID.randomUUID());
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new java.util.ArrayList<>(List.of(item)))
                .build();
        queue.setId(UUID.randomUUID());
        when(playQueueService.getPlayQueue(eq(queue.getId()), any())).thenReturn(Optional.of(queue));
        // The caller's allowed set does not contain the item's library: inaccessible, but present.
        when(libraryAccessService.allowedLibraryIds(any()))
                .thenReturn(Optional.of(java.util.Set.of(UUID.randomUUID())));
        when(mediaLibraryResolver.ofPlayQueueItem(item)).thenReturn(Optional.of(
                app.ister.core.entity.LibraryEntity.builder().id(UUID.randomUUID()).build()));

        Boolean accessible = graphQlTester.document("""
                        { getPlayQueue(id: "%s") { playQueueItems { id accessible } } }
                        """.formatted(queue.getId()))
                .execute()
                .path("getPlayQueue.playQueueItems[0].accessible").entity(Boolean.class).get();
        assertFalse(accessible, "an item outside the caller's allowed libraries is not accessible");
    }
}
