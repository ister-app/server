package app.ister.core.service;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PlayQueueItemEntity;
import app.ister.core.entity.PlaylistEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.PlayQueueSourceType;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PlaylistRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the library a piece of media belongs to, walking up through the parent that is set
 * (movie, episode→show, track→album, chapter→book, podcast episode→podcast). Used together with
 * {@link LibraryAccessService} wherever an id-addressed resource (media file, image, play-queue
 * source) must be checked against the caller's visible libraries.
 */
@Service
@RequiredArgsConstructor
public class MediaLibraryResolver {
    private final MovieRepository movieRepository;
    private final ShowRepository showRepository;
    private final AlbumRepository albumRepository;
    private final BookRepository bookRepository;
    private final PodcastRepository podcastRepository;
    private final LibraryRepository libraryRepository;
    private final MediaFileRepository mediaFileRepository;
    private final ImageRepository imageRepository;
    private final EpisodeRepository episodeRepository;
    private final TrackRepository trackRepository;
    private final ChapterRepository chapterRepository;
    private final PodcastEpisodeRepository podcastEpisodeRepository;
    private final PlaylistRepository playlistRepository;

    /** Transactional so the lazy parent chain can be walked outside an open web session. */
    @Transactional(readOnly = true)
    public Optional<LibraryEntity> ofMediaFileId(UUID mediaFileId) {
        return mediaFileRepository.findById(mediaFileId).flatMap(this::ofMediaFile);
    }

    @Transactional(readOnly = true)
    public Optional<LibraryEntity> ofImageId(UUID imageId) {
        return imageRepository.findById(imageId).flatMap(this::ofImage);
    }

    public Optional<LibraryEntity> ofSource(PlayQueueSourceType sourceType, UUID sourceId) {
        return switch (sourceType) {
            case MOVIE -> movieRepository.findById(sourceId).map(MovieEntity::getLibraryEntity);
            case SHOW -> showRepository.findById(sourceId).map(ShowEntity::getLibraryEntity);
            case ALBUM -> albumRepository.findById(sourceId).map(AlbumEntity::getLibraryEntity);
            case BOOK -> bookRepository.findById(sourceId).map(BookEntity::getLibraryEntity);
            case PODCAST -> podcastRepository.findById(sourceId).map(PodcastEntity::getLibraryEntity);
            case LIBRARY -> libraryRepository.findById(sourceId);
            // An artist spans libraries; ARTIST queue access is enforced per chunk through the
            // library-filtered ranked-track queries instead.
            case ARTIST -> Optional.empty();
            // A filter's sourceId is a saved view, not media; access is enforced per chunk
            // through the caller's allowed-library set, like ARTIST.
            case FILTER -> Optional.empty();
            // A deleted playlist resolves empty, so a running queue keeps playing its items.
            case PLAYLIST -> playlistRepository.findById(sourceId).map(PlaylistEntity::getLibraryEntity);
        };
    }

    /**
     * The library a play-queue item's media belongs to. Empty when the media row was deleted
     * since — callers treat that as accessible, matching the source-access behaviour of
     * play queues (remaining items keep playing).
     */
    @Transactional(readOnly = true)
    public Optional<LibraryEntity> ofPlayQueueItem(PlayQueueItemEntity item) {
        return switch (item.getType()) {
            case MOVIE -> item.getMovieEntityId() == null ? Optional.empty()
                    : movieRepository.findById(item.getMovieEntityId()).map(MovieEntity::getLibraryEntity);
            case EPISODE -> item.getEpisodeEntityId() == null ? Optional.empty()
                    : episodeRepository.findById(item.getEpisodeEntityId())
                            .map(episode -> episode.getShowEntity().getLibraryEntity());
            case TRACK -> item.getTrackEntityId() == null ? Optional.empty()
                    : trackRepository.findById(item.getTrackEntityId())
                            .map(track -> track.getAlbumEntity().getLibraryEntity());
            case CHAPTER -> item.getChapterEntityId() == null ? Optional.empty()
                    : chapterRepository.findById(item.getChapterEntityId())
                            .map(chapter -> chapter.getBookEntity().getLibraryEntity());
            case PODCAST_EPISODE -> item.getPodcastEpisodeEntityId() == null ? Optional.empty()
                    : podcastEpisodeRepository.findById(item.getPodcastEpisodeEntityId())
                            .map(episode -> episode.getPodcastEntity().getLibraryEntity());
            case BOOK, COMIC -> Optional.empty();
        };
    }

    public Optional<LibraryEntity> ofMediaFile(MediaFileEntity mediaFile) {
        if (mediaFile == null) {
            return Optional.empty();
        }
        if (mediaFile.getMovieEntity() != null) {
            return Optional.ofNullable(mediaFile.getMovieEntity().getLibraryEntity());
        }
        if (mediaFile.getEpisodeEntity() != null) {
            return Optional.ofNullable(mediaFile.getEpisodeEntity().getShowEntity().getLibraryEntity());
        }
        if (mediaFile.getTrackEntity() != null) {
            return Optional.ofNullable(mediaFile.getTrackEntity().getAlbumEntity().getLibraryEntity());
        }
        if (mediaFile.getBookEntity() != null) {
            return Optional.ofNullable(mediaFile.getBookEntity().getLibraryEntity());
        }
        if (mediaFile.getChapterEntity() != null) {
            return Optional.ofNullable(mediaFile.getChapterEntity().getBookEntity().getLibraryEntity());
        }
        if (mediaFile.getPodcastEpisodeEntity() != null) {
            return Optional.ofNullable(mediaFile.getPodcastEpisodeEntity().getPodcastEntity().getLibraryEntity());
        }
        return Optional.empty();
    }

    /**
     * Person images have no library; they resolve empty and stay reachable for every user
     * (persons themselves may span libraries).
     */
    public Optional<LibraryEntity> ofImage(ImageEntity image) {
        if (image == null) {
            return Optional.empty();
        }
        if (image.getMovieEntity() != null) {
            return Optional.ofNullable(image.getMovieEntity().getLibraryEntity());
        }
        if (image.getShowEntity() != null) {
            return Optional.ofNullable(image.getShowEntity().getLibraryEntity());
        }
        if (image.getSeasonEntity() != null) {
            return Optional.ofNullable(image.getSeasonEntity().getShowEntity().getLibraryEntity());
        }
        if (image.getEpisodeEntity() != null) {
            return Optional.ofNullable(image.getEpisodeEntity().getShowEntity().getLibraryEntity());
        }
        if (image.getAlbumEntity() != null) {
            return Optional.ofNullable(image.getAlbumEntity().getLibraryEntity());
        }
        if (image.getBookEntity() != null) {
            return Optional.ofNullable(image.getBookEntity().getLibraryEntity());
        }
        if (image.getSeriesEntity() != null) {
            return Optional.ofNullable(image.getSeriesEntity().getLibraryEntity());
        }
        if (image.getPodcastEntity() != null) {
            return Optional.ofNullable(image.getPodcastEntity().getLibraryEntity());
        }
        if (image.getPodcastEpisodeEntity() != null) {
            return Optional.ofNullable(image.getPodcastEpisodeEntity().getPodcastEntity().getLibraryEntity());
        }
        return Optional.empty();
    }
}
