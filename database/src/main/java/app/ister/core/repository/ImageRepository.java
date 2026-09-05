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

    // Every per-parent lookup orders by id. A show reaches the client through several GraphQL
    // paths (showById, the shows list, related, recentlyWatched); without an ORDER BY each one
    // could return the same images in another order, and the player -- which normalizes them
    // into one Show entity -- then swapped a tile's artwork for a moment (a grey blink).
    @Query("select i from ImageEntity i where i.showEntityId = :showEntityId order by i.id")
    List<ImageEntity> findByShowEntityId(@Param("showEntityId") UUID showEntityId);

    boolean existsByEpisodeEntityId(UUID episodeEntityId);

    boolean existsByMovieEntityId(UUID movieEntityId);

    @Query("select i from ImageEntity i where i.episodeEntityId = :episodeEntityId order by i.id")
    List<ImageEntity> findByEpisodeEntityId(@Param("episodeEntityId") UUID episodeEntityId);

    @Query("select i from ImageEntity i where i.movieEntityId = :movieEntityId order by i.id")
    List<ImageEntity> findByMovieEntityId(@Param("movieEntityId") UUID movieEntityId);

    @Query("select i from ImageEntity i where i.personEntityId = :personEntityId order by i.id")
    List<ImageEntity> findByPersonEntityId(@Param("personEntityId") UUID personEntityId);

    @Query("select i from ImageEntity i where i.albumEntityId = :albumEntityId order by i.id")
    List<ImageEntity> findByAlbumEntityId(@Param("albumEntityId") UUID albumEntityId);

    @Query("select i from ImageEntity i where i.bookEntityId = :bookEntityId order by i.id")
    List<ImageEntity> findByBookEntityId(@Param("bookEntityId") UUID bookEntityId);

    @Query("select i from ImageEntity i where i.seriesEntityId = :seriesEntityId order by i.id")
    List<ImageEntity> findBySeriesEntityId(@Param("seriesEntityId") UUID seriesEntityId);

    @Query("select i from ImageEntity i where i.podcastEntityId = :podcastEntityId order by i.id")
    List<ImageEntity> findByPodcastEntityId(@Param("podcastEntityId") UUID podcastEntityId);

    @Query("select i from ImageEntity i where i.podcastEpisodeEntityId = :podcastEpisodeEntityId order by i.id")
    List<ImageEntity> findByPodcastEpisodeEntityId(@Param("podcastEpisodeEntityId") UUID podcastEpisodeEntityId);

    // Batch variants (used by GraphQL @BatchMapping to avoid N+1)
    @Query("select i from ImageEntity i where i.showEntityId in :showEntityIds order by i.id")
    List<ImageEntity> findByShowEntityIdIn(@Param("showEntityIds") Collection<UUID> showEntityIds);

    @Query("select i from ImageEntity i where i.movieEntityId in :movieEntityIds order by i.id")
    List<ImageEntity> findByMovieEntityIdIn(@Param("movieEntityIds") Collection<UUID> movieEntityIds);

    @Query("select i from ImageEntity i where i.episodeEntityId in :episodeEntityIds order by i.id")
    List<ImageEntity> findByEpisodeEntityIdIn(@Param("episodeEntityIds") Collection<UUID> episodeEntityIds);

    @Query("select i from ImageEntity i where i.personEntityId in :personEntityIds order by i.id")
    List<ImageEntity> findByPersonEntityIdIn(@Param("personEntityIds") Collection<UUID> personEntityIds);

    @Query("select i from ImageEntity i where i.albumEntityId in :albumEntityIds order by i.id")
    List<ImageEntity> findByAlbumEntityIdIn(@Param("albumEntityIds") Collection<UUID> albumEntityIds);

    @Query("select i from ImageEntity i where i.bookEntityId in :bookEntityIds order by i.id")
    List<ImageEntity> findByBookEntityIdIn(@Param("bookEntityIds") Collection<UUID> bookEntityIds);

    @Query("select i from ImageEntity i where i.podcastEntityId in :podcastEntityIds order by i.id")
    List<ImageEntity> findByPodcastEntityIdIn(@Param("podcastEntityIds") Collection<UUID> podcastEntityIds);

    @Query("select i from ImageEntity i where i.podcastEpisodeEntityId in :podcastEpisodeEntityIds order by i.id")
    List<ImageEntity> findByPodcastEpisodeEntityIdIn(@Param("podcastEpisodeEntityIds") Collection<UUID> podcastEpisodeEntityIds);

    /** The distinct external providers images were fetched from, for attribution display. */
    @Query("select distinct i.source from ImageEntity i where i.source is not null")
    List<MetadataSource> findDistinctSources();
}