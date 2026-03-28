package app.ister.core.service;

import app.ister.core.entity.StreamTokenEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.repository.StreamTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StreamTokenService {

    private final StreamTokenRepository streamTokenRepository;

    @Scheduled(fixedRate = 3_600_000)
    @Transactional
    public void deleteExpiredTokens() {
        streamTokenRepository.deleteByExpiresAtBefore(Instant.now());
    }

    public StreamTokenEntity createToken(UserEntity userEntity) {
        StreamTokenEntity entity = StreamTokenEntity.builder()
                .userEntity(userEntity)
                .token(UUID.randomUUID())
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();
        return streamTokenRepository.save(entity);
    }

    public Optional<UserEntity> validateToken(String tokenStr) {
        try {
            UUID token = UUID.fromString(tokenStr);
            return streamTokenRepository.findByToken(token)
                    .filter(entity -> entity.getExpiresAt().isAfter(Instant.now()))
                    .map(StreamTokenEntity::getUserEntity);
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
