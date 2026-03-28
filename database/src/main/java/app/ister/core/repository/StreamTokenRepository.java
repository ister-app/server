package app.ister.core.repository;

import app.ister.core.entity.StreamTokenEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface StreamTokenRepository extends CrudRepository<StreamTokenEntity, UUID> {
    Optional<StreamTokenEntity> findByToken(UUID token);

    @Modifying
    @Query("DELETE FROM StreamTokenEntity s WHERE s.expiresAt < :cutoff")
    void deleteByExpiresAtBefore(Instant cutoff);
}
