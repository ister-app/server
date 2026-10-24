package app.ister.api.controller;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.ActivityKind;
import app.ister.core.enums.MediaType;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.GameRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.UserService;
import app.ister.core.status.PlaybackSessionRegistry;
import app.ister.core.status.PlaybackStatusService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Schema wiring of the activity heartbeat: argument binding of both mutations and the enum guard. */
@GraphQlTest(ActivitySessionController.class)
class ActivitySessionControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private UserService userService;
    @MockitoBean
    private BookRepository bookRepository;
    @MockitoBean
    private GameRepository gameRepository;
    @MockitoBean
    private ImageRepository imageRepository;
    @MockitoBean
    private LibraryAccessService libraryAccessService;
    @MockitoBean
    private PlaybackSessionRegistry playbackSessionRegistry;
    @MockitoBean
    private PlaybackStatusService playbackStatusService;
    @MockitoBean
    private DeviceNameResolver deviceNameResolver;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void authenticateAsUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test-user", null,
                        List.of(new SimpleGrantedAuthority("ROLE_user"))));
        when(userService.getOrCreateUser(any()))
                .thenReturn(UserEntity.builder().id(userId).externalId("test-user").name("Test user").build());
        when(playbackSessionRegistry.find(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reportActivityBindsAllArgumentsAndPublishes() {
        UUID sessionId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().id(UUID.randomUUID()).build();
        BookEntity book = BookEntity.builder().libraryEntity(library).name("Book").build();
        book.setId(bookId);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        when(libraryAccessService.canAccess(any(LibraryEntity.class), any())).thenReturn(true);
        when(imageRepository.findByBookEntityId(bookId)).thenReturn(List.of());

        Boolean result = graphQlTester.document("""
                        mutation { reportActivity(sessionId: "%s", mediaType: BOOK, mediaId: "%s",
                            progressFraction: 0.25, progressLabel: "Chapter 2", playState: PAUSED,
                            deviceId: "%s") }
                        """.formatted(sessionId, bookId, deviceId))
                .execute()
                .path("reportActivity").entity(Boolean.class).get();

        assertTrue(result);
        ArgumentCaptor<PlaybackStatusData> captor = ArgumentCaptor.forClass(PlaybackStatusData.class);
        verify(playbackStatusService).publishActivity(captor.capture());
        assertEquals(sessionId, captor.getValue().getPlayQueueId());
        assertEquals(MediaType.BOOK, captor.getValue().getMediaType());
        assertEquals(ActivityKind.READING, captor.getValue().getActivityKind());
        assertEquals(0.25, captor.getValue().getProgressFraction());
        assertEquals("Chapter 2", captor.getValue().getProgressLabel());
        assertEquals(deviceId, captor.getValue().getDeviceId());
    }

    @Test
    void reportActivityRejectsPlaybackMediaTypes() {
        graphQlTester.document("""
                        mutation { reportActivity(sessionId: "%s", mediaType: MOVIE, mediaId: "%s",
                            playState: PLAYING, deviceId: "%s") }
                        """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .execute()
                .errors().expect(error -> error.getMessage() != null);
    }

    @Test
    void endActivityRoundTrips() {
        UUID sessionId = UUID.randomUUID();
        when(playbackSessionRegistry.find(sessionId)).thenReturn(Optional.of(
                PlaybackStatusData.builder().playQueueId(sessionId).userId(userId).build()));

        Boolean result = graphQlTester.document("""
                        mutation { endActivity(sessionId: "%s") }
                        """.formatted(sessionId))
                .execute()
                .path("endActivity").entity(Boolean.class).get();

        assertTrue(result);
        verify(playbackStatusService).publishActivityEnded(sessionId, userId);
    }
}
