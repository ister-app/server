package app.ister.core.repository;

import app.ister.core.entity.MetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MetadataRepository extends JpaRepository<MetadataEntity, UUID> {

    List<MetadataEntity> findByPersonEntityId(UUID personId);

    List<MetadataEntity> findByAlbumEntityId(UUID albumId);

    List<MetadataEntity> findByEpisodeEntityId(UUID episodeId);

    List<MetadataEntity> findByMovieEntityId(UUID movieId);

    List<MetadataEntity> findByTrackEntityId(UUID trackId);

    List<MetadataEntity> findByShowEntityId(UUID showId);

    List<MetadataEntity> findByBookEntityId(UUID bookId);

    List<MetadataEntity> findBySeriesEntityId(UUID seriesId);

    List<MetadataEntity> findByChapterEntityId(UUID chapterId);

    List<MetadataEntity> findByPodcastEntityId(UUID podcastId);

    List<MetadataEntity> findByPodcastEpisodeEntityId(UUID podcastEpisodeId);
}
