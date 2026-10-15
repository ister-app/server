package app.ister.core.service;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.PlayQueueEntity;
import app.ister.core.entity.PlayQueueItemEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.repository.WatchStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Turns a play-queue progress heartbeat into watch statuses: one per listener (the owner plus
 * every follower), with the per-media-type thresholds for "this counts" and "this is watched".
 * Split out of {@link PlayQueueService}, which owns the queue itself; this is the only place the
 * heartbeat writes a {@code WatchStatusEntity}, and it always runs inside the caller's
 * {@code updatePlayQueue} transaction so the continue-watching update lands in the same commit.
 */
@Service
@RequiredArgsConstructor
public class PlayQueueWatchStatusRecorder {

    /** Audiobook chapters record progress from here on; see {@link #recordWatchStatuses}. */
    static final long CHAPTER_PROGRESS_THRESHOLD_MS = 5000;
    /** How much of a track has to be heard before it counts as a play. */
    static final long TRACK_PLAY_THRESHOLD_MS = 30000;

    private final EpisodeRepository episodeRepository;
    private final MovieRepository movieRepository;
    private final TrackRepository trackRepository;
    private final ChapterRepository chapterRepository;
    private final PodcastEpisodeRepository podcastEpisodeRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final WatchStatusService watchStatusService;
    private final ContinueWatchingService continueWatchingService;
    private final MediaFileEpisodeService mediaFileEpisodeService;

    /**
     * Update the watch status of an episode if it's played for more then one minute.
     * Audiobook chapters use a lower threshold: their position is shared with the reader,
     * so the first minute of a chapter has to be recoverable when switching to text.
     * The status is written for every listener: the owner plus each following user —
     * only the owner ever reports progress, so followers cannot flip it back themselves.
     */
    void recordWatchStatuses(long progressInMilliseconds, UUID playQueueItemId, List<UserEntity> listeners, PlayQueueItemEntity playQueueItemEntity) {
        MediaType type = playQueueItemEntity.getType();
        long minimumProgress = type == MediaType.CHAPTER ? CHAPTER_PROGRESS_THRESHOLD_MS : 60000;
        for (UserEntity listener : listeners) {
            if (progressInMilliseconds > minimumProgress) {
                if (type == MediaType.EPISODE) {
                    updateEpisodeWatchStatus(progressInMilliseconds, playQueueItemId, listener, playQueueItemEntity);
                } else if (type == MediaType.MOVIE) {
                    updateMovieWatchStatus(progressInMilliseconds, playQueueItemId, listener, playQueueItemEntity);
                } else if (type == MediaType.CHAPTER) {
                    updateChapterWatchStatus(progressInMilliseconds, listener, playQueueItemEntity);
                } else if (type == MediaType.PODCAST_EPISODE) {
                    updatePodcastEpisodeWatchStatus(progressInMilliseconds, playQueueItemId, listener, playQueueItemEntity);
                }
            }
            // Tracks have their own, shorter threshold (many are barely longer than a minute):
            // a play counts once 30 seconds — or half of a short track — has been heard.
            if (type == MediaType.TRACK) {
                updateTrackWatchStatus(progressInMilliseconds, playQueueItemId, listener, playQueueItemEntity);
            }
        }
    }

    private void updateTrackWatchStatus(long progressInMilliseconds, UUID playQueueItemId, UserEntity listener, PlayQueueItemEntity playQueueItemEntity) {
        trackRepository.findById(playQueueItemEntity.getTrackEntityId()).ifPresent(trackEntity -> {
            if (progressInMilliseconds > trackPlayThreshold(trackEntity)) {
                WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreateForTrack(listener, playQueueItemId, trackEntity);
                updateWatchStatus(progressInMilliseconds, watchStatusEntity, trackEntity.getMediaFileEntities());
            }
        });
    }

    /** Half the track when it is shorter than a minute, {@value #TRACK_PLAY_THRESHOLD_MS} ms otherwise. */
    private long trackPlayThreshold(TrackEntity trackEntity) {
        return trackEntity.getMediaFileEntities().stream().findFirst()
                .map(file -> Math.min(TRACK_PLAY_THRESHOLD_MS, file.getDurationInMilliseconds() / 2))
                .orElse(TRACK_PLAY_THRESHOLD_MS);
    }

    private void updateEpisodeWatchStatus(long progressInMilliseconds, UUID playQueueItemId, UserEntity listener, PlayQueueItemEntity playQueueItemEntity) {
        episodeRepository.findById(playQueueItemEntity.getEpisodeEntityId()).ifPresent(episodeEntity -> {
            WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreate(listener, playQueueItemId, episodeEntity, null);
            // Progress is absolute within the file. For an episode inside a multi-episode file
            // (s04e06-e07.mkv) the episode ends at its slice boundary, not at the file's end —
            // otherwise only the last episode of the file could ever become watched.
            // Of the file that is playing: another version of the episode can be cut differently.
            List<MediaFileEntity> files = mediaFileEpisodeService.filesForEpisode(episodeEntity.getId());
            long endOfEpisode = PlayQueueService.playingFile(files, chosenFileOf(playQueueItemEntity))
                    .map(file -> mediaFileEpisodeService.segmentFor(file.getId(), episodeEntity.getId())
                            .filter(segment -> segment.getDurationInMilliseconds() > 0)
                            .map(segment -> segment.getStartInMilliseconds() + segment.getDurationInMilliseconds())
                            .orElse(file.getDurationInMilliseconds()))
                    .orElse(0L);
            updateWatchStatus(progressInMilliseconds, watchStatusEntity, endOfEpisode);
        });
    }

    private void updateMovieWatchStatus(long progressInMilliseconds, UUID playQueueItemId, UserEntity listener, PlayQueueItemEntity playQueueItemEntity) {
        movieRepository.findById(playQueueItemEntity.getMovieEntityId()).ifPresent(movieEntity -> {
            WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreate(listener, playQueueItemId, null, movieEntity);
            long endOfMovie = PlayQueueService.playingFile(movieEntity.getMediaFileEntities(), chosenFileOf(playQueueItemEntity))
                    .map(MediaFileEntity::getDurationInMilliseconds).orElse(0L);
            updateWatchStatus(progressInMilliseconds, watchStatusEntity, endOfMovie);
        });
    }

    /** The file the playing client reported for this item; null when it is not the current item or none was reported. */
    static UUID chosenFileOf(PlayQueueItemEntity item) {
        PlayQueueEntity queue = item.getPlayQueueEntity();
        return queue != null && item.getId().equals(queue.getCurrentItem()) ? queue.getCurrentMediaFileId() : null;
    }

    private void updateChapterWatchStatus(long progressInMilliseconds, UserEntity listener, PlayQueueItemEntity playQueueItemEntity) {
        chapterRepository.findById(playQueueItemEntity.getChapterEntityId()).ifPresent(chapterEntity -> {
            WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreateForChapter(listener, chapterEntity);
            updateWatchStatus(progressInMilliseconds, watchStatusEntity, chapterEntity.getMediaFileEntities());
        });
    }

    private void updatePodcastEpisodeWatchStatus(long progressInMilliseconds, UUID playQueueItemId, UserEntity listener, PlayQueueItemEntity playQueueItemEntity) {
        podcastEpisodeRepository.findById(playQueueItemEntity.getPodcastEpisodeEntityId()).ifPresent(episodeEntity -> {
            WatchStatusEntity watchStatusEntity = watchStatusService.getOrCreateForPodcastEpisode(listener, playQueueItemId, episodeEntity);
            updateWatchStatus(progressInMilliseconds, watchStatusEntity, episodeEntity.getMediaFileEntities());
        });
    }

    private void updateWatchStatus(long progressInMilliseconds, WatchStatusEntity watchStatusEntity, List<MediaFileEntity> mediaFileEntities) {
        long endOfItem = mediaFileEntities.isEmpty() ? 0 : mediaFileEntities.get(0).getDurationInMilliseconds();
        updateWatchStatus(progressInMilliseconds, watchStatusEntity, endOfItem);
    }

    /** endOfItemInMilliseconds 0 means unknown: progress is recorded but watched is left untouched. */
    private void updateWatchStatus(long progressInMilliseconds, WatchStatusEntity watchStatusEntity, long endOfItemInMilliseconds) {
        watchStatusEntity.setProgressInMilliseconds(progressInMilliseconds);
        if (endOfItemInMilliseconds > 0) {
            // Within a minute of — or past — the end of the item counts as watched. "Past" happens
            // when playback of a multi-episode file runs across an episode boundary.
            watchStatusEntity.setWatched(endOfItemInMilliseconds - progressInMilliseconds < 60000);
        }
        watchStatusRepository.save(watchStatusEntity);
        // Keep the user's continue-watching entry in step with the heartbeat: finishing an episode
        // here is what makes the next one show up in their list.
        continueWatchingService.onWatchStatusChanged(watchStatusEntity);
    }
}
