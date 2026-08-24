package app.ister.core.service;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.repository.WatchStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * The calling user's playback history of one media item: the watch-status rows for it, newest
 * first, plus manual edits — recording a play "as of now" and deleting an entry. Movies, episodes,
 * tracks and podcast episodes have one row per play; books and chapters keep a single upserted row
 * (their history is at most one entry).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PlaybackHistoryService {
    private static final Sort NEWEST_FIRST = Sort.by("dateUpdated").descending();

    private final WatchStatusService watchStatusService;
    private final WatchStatusRepository watchStatusRepository;
    private final ContinueWatchingService continueWatchingService;
    private final MovieRepository movieRepository;
    private final EpisodeRepository episodeRepository;
    private final TrackRepository trackRepository;
    private final ChapterRepository chapterRepository;
    private final BookRepository bookRepository;
    private final PodcastEpisodeRepository podcastEpisodeRepository;

    /** The user's history for one item, newest first. BOOK/COMIC merges in the chapter listens. */
    @Transactional(readOnly = true)
    public List<WatchStatusEntity> history(Authentication authentication, MediaType mediaType, UUID mediaId) {
        String externalId = authentication.getName();
        return switch (mediaType) {
            case MOVIE -> watchStatusRepository
                    .findByUserEntityExternalIdAndMovieEntity(externalId, movie(mediaId), NEWEST_FIRST);
            case EPISODE -> watchStatusRepository
                    .findByUserEntityExternalIdAndEpisodeEntity(externalId, episode(mediaId), NEWEST_FIRST);
            case TRACK -> watchStatusRepository
                    .findByUserEntityExternalIdAndTrackEntity(externalId, track(mediaId), NEWEST_FIRST);
            case PODCAST_EPISODE -> watchStatusRepository
                    .findByUserEntityExternalIdAndPodcastEpisodeEntity(externalId, podcastEpisode(mediaId), NEWEST_FIRST);
            case CHAPTER -> watchStatusRepository
                    .findByUserEntityExternalIdAndChapterEntity(externalId, chapter(mediaId), NEWEST_FIRST);
            case BOOK, COMIC -> bookHistory(externalId, book(mediaId));
        };
    }

    /** The book's reading row plus the listens of its chapters, merged newest first. */
    private List<WatchStatusEntity> bookHistory(String externalId, BookEntity bookEntity) {
        List<WatchStatusEntity> merged = new ArrayList<>(watchStatusRepository
                .findByUserEntityExternalIdAndBookEntity(externalId, bookEntity, NEWEST_FIRST));
        merged.addAll(watchStatusRepository
                .findByUserEntityExternalIdAndChapterEntityBookEntity(externalId, bookEntity, NEWEST_FIRST));
        merged.sort(Comparator.comparing(WatchStatusEntity::getDateUpdated).reversed());
        return merged;
    }

    /**
     * Records a play of the item as of now. The multi-row types get a fresh watched row under a
     * random play-queue-item id (no queue exists for a manual mark; a fresh id also keeps the
     * unique constraints trivially satisfied). BOOK/COMIC/CHAPTER mark their single row finished.
     * Auditing stamps the row with "now" — exactly the played-at moment wanted.
     */
    @Transactional
    public WatchStatusEntity markPlayed(Authentication authentication, MediaType mediaType, UUID mediaId) {
        WatchStatusEntity entity = switch (mediaType) {
            case MOVIE -> watchStatusService.getOrCreate(authentication, UUID.randomUUID(), null, movie(mediaId));
            case EPISODE -> watchStatusService.getOrCreate(authentication, UUID.randomUUID(), episode(mediaId), null);
            case TRACK -> watchStatusService.getOrCreateForTrack(authentication, UUID.randomUUID(), track(mediaId));
            case PODCAST_EPISODE -> watchStatusService
                    .getOrCreateForPodcastEpisode(authentication, UUID.randomUUID(), podcastEpisode(mediaId));
            case CHAPTER -> watchStatusService.getOrCreateForChapter(authentication, chapter(mediaId));
            case BOOK, COMIC -> markBookRead(watchStatusService.getOrCreateForBook(authentication, book(mediaId)));
        };
        entity.setWatched(true);
        watchStatusRepository.save(entity);
        continueWatchingService.onWatchStatusChanged(entity);
        return entity;
    }

    private static WatchStatusEntity markBookRead(WatchStatusEntity entity) {
        entity.setReadingProgress(1.0);
        return entity;
    }

    /**
     * Deletes one of the calling user's own watch-status rows. Foreign rows are refused (false).
     * The continue-watching entry derived from the row may now be stale, so the user's list is
     * rebuilt — the existing repair path, cheap enough for a rare manual action.
     */
    @Transactional
    public boolean deleteWatchStatus(Authentication authentication, UUID id) {
        Optional<WatchStatusEntity> entity = watchStatusRepository.findById(id);
        if (entity.isEmpty()) {
            return false;
        }
        UserEntity owner = entity.get().getUserEntity();
        if (!owner.getExternalId().equals(authentication.getName())) {
            log.warn("Refusing to delete watch status {} owned by another user", id);
            return false;
        }
        watchStatusRepository.delete(entity.get());
        continueWatchingService.rebuildForUser(owner);
        return true;
    }

    /** The library the item belongs to, for the caller's access check; empty when it does not exist. */
    @Transactional(readOnly = true)
    public Optional<app.ister.core.entity.LibraryEntity> libraryOf(MediaType mediaType, UUID mediaId) {
        return switch (mediaType) {
            case MOVIE -> movieRepository.findById(mediaId).map(MovieEntity::getLibraryEntity);
            case EPISODE -> episodeRepository.findById(mediaId)
                    .map(episode -> episode.getShowEntity().getLibraryEntity());
            case TRACK -> trackRepository.findById(mediaId)
                    .map(t -> t.getAlbumEntity().getLibraryEntity());
            case CHAPTER -> chapterRepository.findById(mediaId)
                    .map(c -> c.getBookEntity().getLibraryEntity());
            case BOOK, COMIC -> bookRepository.findById(mediaId).map(BookEntity::getLibraryEntity);
            case PODCAST_EPISODE -> podcastEpisodeRepository.findById(mediaId)
                    .map(pe -> pe.getPodcastEntity().getLibraryEntity());
        };
    }

    private MovieEntity movie(UUID id) {
        return movieRepository.findById(id).orElseThrow(() -> notFound("Movie", id));
    }

    private EpisodeEntity episode(UUID id) {
        return episodeRepository.findById(id).orElseThrow(() -> notFound("Episode", id));
    }

    private TrackEntity track(UUID id) {
        return trackRepository.findById(id).orElseThrow(() -> notFound("Track", id));
    }

    private ChapterEntity chapter(UUID id) {
        return chapterRepository.findById(id).orElseThrow(() -> notFound("Chapter", id));
    }

    private BookEntity book(UUID id) {
        return bookRepository.findById(id).orElseThrow(() -> notFound("Book", id));
    }

    private PodcastEpisodeEntity podcastEpisode(UUID id) {
        return podcastEpisodeRepository.findById(id).orElseThrow(() -> notFound("PodcastEpisode", id));
    }

    private static NoSuchElementException notFound(String type, UUID id) {
        return new NoSuchElementException(type + " not found: " + id);
    }
}
