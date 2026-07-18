package app.ister.core.repository;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.MetadataSource;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImageRepository extends CrudRepository<ImageEntity, UUID> {
    Page<ImageEntity> findAll(Pageable pageable);

    Optional<ImageEntity> findByDirectoryEntityAndPath(DirectoryEntity directoryEntity, String path);

    Optional<ImageEntity> findByDirectoryEntityIdAndPath(UUID directoryEntityId, String path);

    /**
     * All image file paths referenced for a cache directory. Used by the cache-cleanup sweep to
     * decide which files on disk are still referenced (everything else is a zombie).
     */
    @Query("select i.path from ImageEntity i where i.directoryEntityId = :directoryEntityId and i.path is not null")
    List<String> findPathsByDirectoryEntityId(@Param("directoryEntityId") UUID directoryEntityId);

    List<ImageEntity> findByDirectoryEntity(DirectoryEntity directoryEntity);

    /**
     * First chunk of a blur-hash sweep. Ordered by id so the sweep can resume with a keyset
     * cursor: an image whose blur-hash can never be computed (e.g. a CMYK JPEG) stays
     * {@code null} forever, so a plain {@code LIMIT} without a cursor would hand back the same
     * failing rows every round and the sweep would never terminate.
     */
    List<ImageEntity> findByDirectoryEntityIdAndBlurHashIsNullOrderById(UUID directoryEntityId, Limit limit);

    /** Next chunk of a blur-hash sweep, resuming after the last id of the previous chunk. */
    List<ImageEntity> findByDirectoryEntityIdAndBlurHashIsNullAndIdGreaterThanOrderById(
            UUID directoryEntityId, UUID afterId, Limit limit);

    List<ImageEntity> findByShowEntityId(UUID showEntityId);

    boolean existsByEpisodeEntityId(UUID episodeEntityId);

    boolean existsByMovieEntityId(UUID movieEntityId);

    List<ImageEntity> findByEpisodeEntityId(UUID episodeEntityId);

    List<ImageEntity> findByMovieEntityId(UUID movieEntityId);

    List<ImageEntity> findByPersonEntityId(UUID personEntityId);

    List<ImageEntity> findByAlbumEntityId(UUID albumEntityId);

    List<ImageEntity> findByBookEntityId(UUID bookEntityId);

    List<ImageEntity> findBySeriesEntityId(UUID seriesEntityId);

    List<ImageEntity> findByPodcastEntityId(UUID podcastEntityId);

    List<ImageEntity> findByPodcastEpisodeEntityId(UUID podcastEpisodeEntityId);

    // Batch variants (used by GraphQL @BatchMapping to avoid N+1)
    List<ImageEntity> findByShowEntityIdIn(Collection<UUID> showEntityIds);

    List<ImageEntity> findByPersonEntityIdIn(Collection<UUID> personEntityIds);

    List<ImageEntity> findByAlbumEntityIdIn(Collection<UUID> albumEntityIds);

    List<ImageEntity> findByBookEntityIdIn(Collection<UUID> bookEntityIds);

    List<ImageEntity> findByPodcastEntityIdIn(Collection<UUID> podcastEntityIds);

    List<ImageEntity> findByPodcastEpisodeEntityIdIn(Collection<UUID> podcastEpisodeEntityIds);

    /** The distinct external providers images were fetched from, for attribution display. */
    @Query("select distinct i.source from ImageEntity i where i.source is not null")
    List<MetadataSource> findDistinctSources();
}