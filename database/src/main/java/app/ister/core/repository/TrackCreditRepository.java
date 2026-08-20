package app.ister.core.repository;

import app.ister.core.entity.TrackCreditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TrackCreditRepository extends JpaRepository<TrackCreditEntity, UUID> {
    List<TrackCreditEntity> findByTrackEntity_IdIn(Collection<UUID> trackIds);

    List<TrackCreditEntity> findByTrackEntity_IdOrderByPositionAsc(UUID trackId);

    void deleteByTrackEntity_Id(UUID trackId);
}
