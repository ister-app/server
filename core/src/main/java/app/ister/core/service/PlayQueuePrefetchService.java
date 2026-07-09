package app.ister.core.service;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PlayQueueEntity;
import app.ister.core.entity.PlayQueueItemEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.SubtitleFormat;
import app.ister.core.eventdata.TranscodeRequestedData;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prefetches (pre-transcodes) the upcoming play queue item(s) when the current item is
 * almost finished, in the stream settings the client last reported, so playback continues
 * without waiting on the transcoder. All requests are background work: the transcoder
 * only runs them on spare capacity and preempts them for interactive playback.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PlayQueuePrefetchService {

    private final MovieRepository movieRepository;
    private final EpisodeRepository episodeRepository;
    private final TrackRepository trackRepository;
    private final MessageSender messageSender;

    @Value("${app.ister.server.prefetch.enabled:true}")
    private boolean prefetchEnabled;

    /** Prefetch the next video item when less than this many seconds of the current item remain. */
    @Value("${app.ister.server.prefetch.video-threshold-seconds:120}")
    private long videoThresholdSeconds;

    /** Prefetch the next track(s) when less than this many seconds remain (or half the track is played). */
    @Value("${app.ister.server.prefetch.track-threshold-seconds:60}")
    private long trackThresholdSeconds;

    /** Number of upcoming tracks to prefetch; video items always prefetch just the next one. */
    @Value("${app.ister.server.prefetch.track-depth:2}")
    private int trackDepth;

    /** How long the transcoder keeps a prefetched item's HLS cache. */
    @Value("${app.ister.server.prefetch.keep-hours:24}")
    private long keepHours;

    /** Queue items already prefetched this application run; keeps the frequent progress updates idempotent. */
    private final Set<UUID> prefetchedItems = ConcurrentHashMap.newKeySet();

    /**
     * Called on every play queue progress update. Video items prefetch only the next item;
     * tracks are short, so the next {@code trackDepth} tracks are prefetched.
     */
    @Transactional(readOnly = true)
    public void maybePrefetchNext(PlayQueueEntity queue, UUID currentItemId, long progressInMilliseconds) {
        if (!prefetchEnabled) {
            return;
        }
        List<PlayQueueItemEntity> items = queue.getItems();
        int currentIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId().equals(currentItemId)) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex == -1) {
            return;
        }
        PlayQueueItemEntity current = items.get(currentIndex);
        long durationMs = durationOf(current);
        if (durationMs <= 0) {
            return;
        }
        long remainingMs = durationMs - progressInMilliseconds;
        boolean nearEnd = current.getType() == MediaType.TRACK
                ? remainingMs <= trackThresholdSeconds * 1000 || progressInMilliseconds * 2 >= durationMs
                : remainingMs <= videoThresholdSeconds * 1000;
        if (!nearEnd) {
            return;
        }
        int depth = current.getType() == MediaType.TRACK ? trackDepth : 1;
        for (int i = currentIndex + 1; i <= currentIndex + depth && i < items.size(); i++) {
            PlayQueueItemEntity next = items.get(i);
            if (prefetchedItems.add(next.getId())) {
                prefetchItem(queue, next);
            }
        }
    }

    private long durationOf(PlayQueueItemEntity item) {
        return mediaFilesOf(item).stream()
                .findFirst()
                .map(MediaFileEntity::getDurationInMilliseconds)
                .orElse(0L);
    }

    private List<MediaFileEntity> mediaFilesOf(PlayQueueItemEntity item) {
        return switch (item.getType()) {
            case MOVIE -> movieRepository.findById(item.getMovieEntityId())
                    .map(MovieEntity::getMediaFileEntities).orElse(List.of());
            case EPISODE -> episodeRepository.findById(item.getEpisodeEntityId())
                    .map(EpisodeEntity::getMediaFileEntities).orElse(List.of());
            case TRACK -> trackRepository.findById(item.getTrackEntityId())
                    .map(TrackEntity::getMediaFileEntities).orElse(List.of());
        };
    }

    /**
     * Requests a background pre-transcode of every analyzed media file of the item, in the
     * stream settings the client last reported (falling back to the pre-transcode defaults).
     */
    private void prefetchItem(PlayQueueEntity queue, PlayQueueItemEntity item) {
        long keepUntilEpochMillis = System.currentTimeMillis() + Duration.ofHours(keepHours).toMillis();
        for (MediaFileEntity mediaFile : mediaFilesOf(item)) {
            if (mediaFile.getMediaFileStreamEntity() == null || mediaFile.getMediaFileStreamEntity().isEmpty()) {
                log.debug("Skipping prefetch of unanalyzed media file {}", mediaFile.getId());
                continue;
            }
            log.debug("Prefetching play queue item {} (media file {})", item.getId(), mediaFile.getId());
            messageSender.sendTranscodeRequested(TranscodeRequestedData.builder()
                            .eventType(EventType.TRANSCODE_REQUESTED)
                            .mediaFileId(mediaFile.getId())
                            .direct(queue.getStreamDirect() != null ? queue.getStreamDirect() : Boolean.FALSE)
                            .transcode(queue.getStreamTranscode() != null ? queue.getStreamTranscode() : Boolean.TRUE)
                            .subtitleFormat(queue.getStreamSubtitleFormat() != null ? queue.getStreamSubtitleFormat() : SubtitleFormat.WEBVTT)
                            .preTranscode(true)
                            .keepUntilEpochMillis(keepUntilEpochMillis)
                            .build(),
                    mediaFile.getDirectoryEntity().getName());
        }
    }
}
