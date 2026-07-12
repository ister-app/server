package app.ister.core.repository;

import app.ister.core.entity.PodcastEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PodcastRepository extends JpaRepository<PodcastEntity, UUID> {
    Optional<PodcastEntity> findByFeedUrl(String feedUrl);

    Page<PodcastEntity> findByLibraryEntityIdAndActiveTrue(UUID libraryId, Pageable pageable);

    Page<PodcastEntity> findByActiveTrue(Pageable pageable);

    List<PodcastEntity> findByActiveTrue();
}
