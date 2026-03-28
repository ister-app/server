package app.ister.api.controller;

import app.ister.core.entity.StreamTokenEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.service.StreamTokenService;
import app.ister.core.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamTokenControllerTest {

    @InjectMocks
    private StreamTokenController subject;

    @Mock
    private StreamTokenService streamTokenService;

    @Mock
    private UserService userService;

    @Mock
    private Authentication authentication;

    @Test
    void createStreamTokenReturnsTokenFromService() {
        UserEntity user = UserEntity.builder().externalId("user-1").build();
        StreamTokenEntity token = StreamTokenEntity.builder()
                .userEntity(user)
                .token(UUID.randomUUID())
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(streamTokenService.createToken(user)).thenReturn(token);

        StreamTokenEntity result = subject.createStreamToken(authentication);

        assertSame(token, result);
        verify(userService).getOrCreateUser(authentication);
        verify(streamTokenService).createToken(user);
    }

    @Test
    void createStreamTokenPassesAuthenticationToUserService() {
        UserEntity user = UserEntity.builder().externalId("test-user").build();
        StreamTokenEntity token = StreamTokenEntity.builder()
                .userEntity(user)
                .token(UUID.randomUUID())
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(streamTokenService.createToken(user)).thenReturn(token);

        subject.createStreamToken(authentication);

        verify(userService).getOrCreateUser(authentication);
    }
}
