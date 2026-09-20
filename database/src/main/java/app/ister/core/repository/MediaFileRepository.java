package app.ister.core.repository;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MovieEntity;
import org.springframework.data.jpa.repository.Modifying;

public interface MediaFileRepository extends CrudRepository<MediaFileEntity, UUID> {

    Optional<MediaFileEntity> findByDirectoryEntityAndPath(DirectoryEntity directoryEntity, String path);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MediaFileEntity m WHERE m.directoryEntity = :directoryEntity AND m.path = :path")
    Optional<MediaFileEntity> findByDirectoryEntityAndPathForUpdate(@Param("directoryEntity") DirectoryEntity directoryEntity, @Param("path") String path);

    List<MediaFileEntity> findByDirectoryEntity(DirectoryEntity directoryEntity);

    boolean existsByTrackEntityId(UUID trackId);

    List<MediaFileEntity> findByTrackEntity_AlbumEntityId(UUID albumId);

    /** Ordered like the entity collections: "the first file" of an item with several versions must be stable. */
    @Query("SELECT m FROM MediaFileEntity m WHERE m.episodeEntity.id = :episodeId ORDER BY m.id ASC")
    List<MediaFileEntity> findByEpisodeEntityId(@Param("episodeId") UUID episodeId);

    @Query("SELECT m FROM MediaFileEntity m WHERE m.movieEntity.id = :movieId ORDER BY m.id ASC")
    List<MediaFileEntity> findByMovieEntityId(@Param("movieId") UUID movieId);

    List<MediaFileEntity> findByTrackEntityId(UUID trackId);

    List<MediaFileEntity> findByChapterEntityId(UUID chapterId);

    /** Epub files attached directly to a book. */
    List<MediaFileEntity> findByBookEntityId(UUID bookId);

    /** Batch variant for the metadata backfill: epub files of many books in one query. */
    List<MediaFileEntity> findByBookEntityIdIn(Collection<UUID> bookIds);

    /** Batch variant for the metadata backfill: audio files of many tracks in one query. */
    List<MediaFileEntity> findByTrackEntityIdIn(Collection<UUID> trackIds);

    List<MediaFileEntity> findByChapterEntity_BookEntityId(UUID bookId);

    List<MediaFileEntity> findByPodcastEpisodeEntityId(UUID podcastEpisodeId);

    boolean existsByPodcastEpisodeEntityId(UUID podcastEpisodeId);

    /** All media-file paths in a directory (cache cleanup: downloaded podcast audio is referenced). */
    @Query("SELECT m.path FROM MediaFileEntity m WHERE m.directoryEntityId = :directoryEntityId")
    List<String> findPathsByDirectoryEntityId(@Param("directoryEntityId") UUID directoryEntityId);

    /** Which of the given paths are already known in a directory (upload preview: "exists" check). */
    @Query("SELECT m.path FROM MediaFileEntity m WHERE m.directoryEntityId = :directoryEntityId AND m.path IN :paths")
    List<String> findPathsByDirectoryEntityIdAndPathIn(@Param("directoryEntityId") UUID directoryEntityId,
                                                       @Param("paths") Collection<String> paths);

    /** Downloaded podcast episodes in a directory, oldest first (retention sweep). */
    List<MediaFileEntity> findByDirectoryEntityIdAndPodcastEpisodeEntityIsNotNullOrderByDateCreatedAsc(UUID directoryEntityId);


    /**
     * A file of the library whose path contains the given fragment, e.g. {@code "Name (2007)"}: the
     * way the scanner finds the movie a merged-away duplicate name now belongs to.
     */
    Optional<MediaFileEntity> findFirstByMovieEntityLibraryEntityAndPathContaining(LibraryEntity libraryEntity, String fragment);

    @Modifying
    @Query("UPDATE MediaFileEntity m SET m.movieEntity = :target WHERE m.movieEntity = :source")
    int moveToMovie(@Param("source") MovieEntity source, @Param("target") MovieEntity target);
}
