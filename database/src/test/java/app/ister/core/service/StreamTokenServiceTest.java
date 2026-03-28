package app.ister.core.service;

import app.ister.core.entity.StreamTokenEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.repository.StreamTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamTokenServiceTest {

    @InjectMocks
    private StreamTokenService subject;

    @Mock
    private StreamTokenRepository streamTokenRepository;

    // ========== createToken ==========

    @Test
    void createTokenSavesEntityAndReturnsIt() {
        UserEntity user = UserEntity.builder().externalId("user-1").build();
        StreamTokenEntity saved = StreamTokenEntity.builder()
                .userEntity(user)
                .token(UUID.randomUUID())
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();
        when(streamTokenRepository.save(any())).thenReturn(saved);

        StreamTokenEntity result = subject.createToken(user);

        assertSame(saved, result);
        ArgumentCaptor<StreamTokenEntity> captor = ArgumentCaptor.forClass(StreamTokenEntity.class);
        verify(streamTokenRepository).save(captor.capture());
        StreamTokenEntity entity = captor.getValue();
        assertEquals(user, entity.getUserEntity());
        assertNotNull(entity.getToken());
        // expiresAt should be approximately 24 hours from now
        Instant expected = Instant.now().plus(24, ChronoUnit.HOURS);
        assertTrue(entity.getExpiresAt().isAfter(Instant.now()));
        assertTrue(entity.getExpiresAt().isBefore(expected.plusSeconds(5)));
    }

    @Test
    void createTokenGeneratesUniqueTokens() {
        UserEntity user = UserEntity.builder().externalId("user-1").build();
        when(streamTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StreamTokenEntity t1 = subject.createToken(user);
        StreamTokenEntity t2 = subject.createToken(user);

        assertNotEquals(t1.getToken(), t2.getToken());
    }

    // ========== validateToken ==========

    @Test
    void validateTokenReturnsUserForValidNonExpiredToken() {
        UUID tokenUuid = UUID.randomUUID();
        UserEntity user = UserEntity.builder().externalId("user-abc").build();
        StreamTokenEntity entity = StreamTokenEntity.builder()
                .userEntity(user)
                .token(tokenUuid)
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        when(streamTokenRepository.findByToken(tokenUuid)).thenReturn(Optional.of(entity));

        Optional<UserEntity> result = subject.validateToken(tokenUuid.toString());

        assertTrue(result.isPresent());
        assertEquals(user, result.get());
    }

    @Test
    void validateTokenReturnsEmptyForExpiredToken() {
        UUID tokenUuid = UUID.randomUUID();
        StreamTokenEntity entity = StreamTokenEntity.builder()
                .userEntity(UserEntity.builder().externalId("u").build())
                .token(tokenUuid)
                .expiresAt(Instant.now().minus(1, ChronoUnit.SECONDS))
                .build();
        when(streamTokenRepository.findByToken(tokenUuid)).thenReturn(Optional.of(entity));

        Optional<UserEntity> result = subject.validateToken(tokenUuid.toString());

        assertTrue(result.isEmpty());
    }

    @Test
    void validateTokenReturnsEmptyWhenTokenNotFound() {
        UUID tokenUuid = UUID.randomUUID();
        when(streamTokenRepository.findByToken(tokenUuid)).thenReturn(Optional.empty());

        Optional<UserEntity> result = subject.validateToken(tokenUuid.toString());

        assertTrue(result.isEmpty());
    }

    @Test
    void validateTokenReturnsEmptyForInvalidUuid() {
        Optional<UserEntity> result = subject.validateToken("not-a-uuid");

        assertTrue(result.isEmpty());
        verifyNoInteractions(streamTokenRepository);
    }

    @Test
    void validateTokenReturnsEmptyForNullLikeString() {
        Optional<UserEntity> result = subject.validateToken("null");

        assertTrue(result.isEmpty());
        verifyNoInteractions(streamTokenRepository);
    }

    // ========== deleteExpiredTokens ==========

    @Test
    void deleteExpiredTokensDelegatesToRepository() {
        subject.deleteExpiredTokens();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(streamTokenRepository).deleteByExpiresAtBefore(captor.capture());
        // The cutoff should be approximately now
        Instant cutoff = captor.getValue();
        assertTrue(cutoff.isAfter(Instant.now().minusSeconds(5)));
        assertTrue(cutoff.isBefore(Instant.now().plusSeconds(1)));
    }
}
