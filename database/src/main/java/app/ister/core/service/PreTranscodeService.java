package app.ister.core.service;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.UserRepository;
import app.ister.core.repository.WatchStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PreTranscodeService {

    private static final int RECENT_DAYS = 7;

    private final UserRepository userRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final EpisodeRepository episodeRepository;
    private final MovieRepository movieRepository;

    /**
     * Collects the media file IDs to pre-transcode for the given disk, across all users.
     * <p>
     * Episodes: the recently-watched episode itself is always included; if it was watched
     * within the last 7 days, the next episode in show order is also included.
     * Movies: every recently-watched movie is included.
     * All DB access is performed within this transaction to avoid LazyInitializationException.
     *
     * @param diskName the directory name to filter media files by
     * @return deduplicated set of media file IDs to pre-transcode
     */
    @Transactional(readOnly = true)
    public Set<UUID> collectMediaFileIdsToPreTranscode(String diskName) {
        Map<UUID, List<EpisodeEntity>> episodesByShow = new HashMap<>();
        Set<UUID> mediaFileIds = new LinkedHashSet<>();
        Instant recentCutoff = Instant.now().minus(Duration.ofDays(RECENT_DAYS));

        userRepository.findAll().forEach(user -> {
            collectEpisodeMediaFileIds(user.getId(), diskName, recentCutoff, episodesByShow, mediaFileIds);
            collectMovieMediaFileIds(user.getId(), diskName, mediaFileIds);
        });

        log.debug("Collected {} media file IDs to pre-transcode for disk: {}", mediaFileIds.size(), diskName);
        return mediaFileIds;
    }

    private void collectEpisodeMediaFileIds(UUID userId, String diskName, Instant recentCutoff,
                                             Map<UUID, List<EpisodeEntity>> episodesByShow,
                                             Set<UUID> mediaFileIds) {
        List<Object[]> rows = watchStatusRepository.findRecentEpisodesWithDateByUserId(userId);
        for (Object[] row : rows) {
            UUID episodeId = UUID.fromString(row[0].toString());
            UUID showId = UUID.fromString(row[1].toString());
            Instant lastWatched = (Instant) row[2];
            episodeRepository.findById(episodeId).ifPresent(lastWatchedEpisode -> {
                addMediaFileIds(lastWatchedEpisode.getMediaFileEntities(), diskName, mediaFileIds);
                if (lastWatched.isAfter(recentCutoff)) {
                    addNextEpisodeMediaFileIds(showId, lastWatchedEpisode, diskName, episodesByShow, mediaFileIds);
                }
            });
        }
    }

    private void addNextEpisodeMediaFileIds(UUID showId, EpisodeEntity lastWatched, String diskName,
                                             Map<UUID, List<EpisodeEntity>> episodesByShow,
                                             Set<UUID> mediaFileIds) {
        List<EpisodeEntity> showEpisodes = episodesByShow.computeIfAbsent(showId,
                id -> episodeRepository.findByShowEntityId(id,
                        Sort.by("seasonEntity.number").ascending().and(Sort.by("number").ascending())));
        for (int i = 0; i < showEpisodes.size(); i++) {
            if (showEpisodes.get(i).getId().equals(lastWatched.getId()) && i + 1 < showEpisodes.size()) {
                addMediaFileIds(showEpisodes.get(i + 1).getMediaFileEntities(), diskName, mediaFileIds);
                break;
            }
        }
    }

    private void collectMovieMediaFileIds(UUID userId, String diskName, Set<UUID> mediaFileIds) {
        watchStatusRepository.findRecentMovieIdsByUserId(userId).forEach(movieIdStr -> {
            UUID movieId = UUID.fromString(movieIdStr);
            movieRepository.findById(movieId).ifPresent(movie ->
                    addMediaFileIds(movie.getMediaFileEntities(), diskName, mediaFileIds));
        });
    }

    private static void addMediaFileIds(List<MediaFileEntity> mediaFiles, String diskName, Set<UUID> mediaFileIds) {
        mediaFiles.stream()
                .filter(mf -> diskName.equals(mf.getDirectoryEntity().getName()))
                .map(MediaFileEntity::getId)
                .forEach(mediaFileIds::add);
    }
}
