package app.ister.core.repository;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaFileRepository extends CrudRepository<MediaFileEntity, UUID> {

    Optional<MediaFileEntity> findByDirectoryEntityAndPath(DirectoryEntity directoryEntity, String path);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MediaFileEntity m WHERE m.directoryEntity = :directoryEntity AND m.path = :path")
    Optional<MediaFileEntity> findByDirectoryEntityAndPathForUpdate(@Param("directoryEntity") DirectoryEntity directoryEntity, @Param("path") String path);

    List<MediaFileEntity> findByDirectoryEntity(DirectoryEntity directoryEntity);

    boolean existsByTrackEntityId(UUID trackId);

    List<MediaFileEntity> findByTrackEntity_AlbumEntityId(UUID albumId);

    List<MediaFileEntity> findByEpisodeEntityId(UUID episodeId);

    List<MediaFileEntity> findByMovieEntityId(UUID movieId);

    List<MediaFileEntity> findByTrackEntityId(UUID trackId);

    List<MediaFileEntity> findByChapterEntityId(UUID chapterId);

    /** Epub files attached directly to a book. */
    List<MediaFileEntity> findByBookEntityId(UUID bookId);

    List<MediaFileEntity> findByChapterEntity_BookEntityId(UUID bookId);

    List<MediaFileEntity> findByPodcastEpisodeEntityId(UUID podcastEpisodeId);

    boolean existsByPodcastEpisodeEntityId(UUID podcastEpisodeId);

    /** All media-file paths in a directory (cache cleanup: downloaded podcast audio is referenced). */
    @Query("SELECT m.path FROM MediaFileEntity m WHERE m.directoryEntityId = :directoryEntityId")
    List<String> findPathsByDirectoryEntityId(@Param("directoryEntityId") UUID directoryEntityId);

    /** Downloaded podcast episodes in a directory, oldest first (retention sweep). */
    List<MediaFileEntity> findByDirectoryEntityIdAndPodcastEpisodeEntityIsNotNullOrderByDateCreatedAsc(UUID directoryEntityId);
}
