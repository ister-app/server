package app.ister.api.controller;

import app.ister.core.entity.UserEntity;
import app.ister.core.enums.FollowResult;
import app.ister.core.eventdata.PlaybackStatusData;
import app.ister.core.service.PlayQueueService;
import app.ister.core.service.UserService;
import app.ister.core.status.FollowerStatusService;
import app.ister.core.status.PlaybackSessionRegistry;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Schema-wiring test for the followPlayQueue mutation and its FollowResult enum. */
@GraphQlTest(PlayQueueFollowController.class)
class PlayQueueFollowControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private PlayQueueService playQueueService;

    @MockitoBean
    private PlaybackSessionRegistry playbackSessionRegistry;

    @MockitoBean
    private FollowerStatusService followerStatusService;

    @MockitoBean
    private UserService userService;

    @BeforeEach
    void authenticateAsUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "test-user", null,
                        List.of(new SimpleGrantedAuthority("ROLE_user"))));
        when(userService.getOrCreateUser(any()))
                .thenReturn(UserEntity.builder().id(UUID.randomUUID()).build());
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void followPlayQueueRoundTripsTheFollowResult() {
        UUID queueId = UUID.randomUUID();
        when(playbackSessionRegistry.find(queueId)).thenReturn(Optional.of(PlaybackStatusData.builder().build()));
        when(playQueueService.checkFollowAccess(eq(queueId), any())).thenReturn(FollowResult.NO_LIBRARY_ACCESS);

        graphQlTester.document("""
                        mutation { followPlayQueue(playQueueId: "%s", deviceId: "device-a", active: true) }
                        """.formatted(queueId))
                .execute()
                .path("followPlayQueue").entity(String.class).isEqualTo("NO_LIBRARY_ACCESS");
    }

    @Test
    void followPlayQueueWithoutSessionIsNotFound() {
        UUID queueId = UUID.randomUUID();
        when(playbackSessionRegistry.find(queueId)).thenReturn(Optional.empty());

        String result = graphQlTester.document("""
                        mutation { followPlayQueue(playQueueId: "%s", deviceId: "device-a", active: true) }
                        """.formatted(queueId))
                .execute()
                .path("followPlayQueue").entity(String.class).get();
        assertEquals("NOT_FOUND", result);
    }
}
