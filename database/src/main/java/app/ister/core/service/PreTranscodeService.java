package app.ister.core.service;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.UserRepository;
import app.ister.core.repository.WatchStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    private final UserRepository userRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final EpisodeRepository episodeRepository;
    private final MovieRepository movieRepository;

    /**
     * How recently (in days) an episode must have been watched for the <em>next</em> episode
     * of that show to be pre-transcoded as well. Effectively capped at 150 days: the
     * watch-history queries only look back that far.
     */
    @Value("${app.ister.server.pretranscode.next-episode-recent-days:150}")
    private int nextEpisodeRecentDays;

    /** A media file that should be pre-transcoded but has no analyzed streams yet. */
    public record UnanalyzedMediaFile(UUID mediaFileId, UUID directoryId, String path,
                                      UUID episodeId, UUID movieId) {
    }

    /**
     * @param mediaFileIds    deduplicated media file IDs ready for pre-transcoding
     * @param unanalyzedFiles media files that first need (re-)analysis before they can be transcoded
     */
    public record PreTranscodeCollection(Set<UUID> mediaFileIds, Set<UnanalyzedMediaFile> unanalyzedFiles) {
    }

    /**
     * Collects the media files to pre-transcode for the given disk, across all users.
     * <p>
     * Episodes: the recently-watched episode itself is always included; if it was watched
     * within the last {@code nextEpisodeRecentDays} days, the next episode in show order is
     * also included.
     * Movies: every recently-watched movie is included.
     * Media files without analyzed streams cannot be transcoded and are returned separately
     * so the caller can trigger (re-)analysis.
     * All DB access is performed within this transaction to avoid LazyInitializationException.
     *
     * @param diskName the directory name to filter media files by
     */
    @Transactional(readOnly = true)
    public PreTranscodeCollection collectMediaFilesToPreTranscode(String diskName) {
        Map<UUID, List<EpisodeEntity>> episodesByShow = new HashMap<>();
        PreTranscodeCollection result = new PreTranscodeCollection(new LinkedHashSet<>(), new LinkedHashSet<>());
        Instant recentCutoff = Instant.now().minus(Duration.ofDays(nextEpisodeRecentDays));

        userRepository.findAll().forEach(user -> {
            collectEpisodeMediaFiles(user.getId(), diskName, recentCutoff, episodesByShow, result);
            collectMovieMediaFiles(user.getId(), diskName, result);
        });

        log.debug("Collected {} media files ({} unanalyzed) to pre-transcode for disk: {}",
                result.mediaFileIds().size(), result.unanalyzedFiles().size(), diskName);
        return result;
    }

    private void collectEpisodeMediaFiles(UUID userId, String diskName, Instant recentCutoff,
                                          Map<UUID, List<EpisodeEntity>> episodesByShow,
                                          PreTranscodeCollection result) {
        List<Object[]> rows = watchStatusRepository.findRecentEpisodesWithDateByUserId(userId);
        for (Object[] row : rows) {
            UUID episodeId = UUID.fromString(row[0].toString());
            UUID showId = UUID.fromString(row[1].toString());
            Instant lastWatched = (Instant) row[2];
            episodeRepository.findById(episodeId).ifPresent(lastWatchedEpisode -> {
                addMediaFiles(lastWatchedEpisode.getMediaFileEntities(), diskName, result,
                        lastWatchedEpisode.getId(), null);
                if (lastWatched.isAfter(recentCutoff)) {
                    addNextEpisodeMediaFiles(showId, lastWatchedEpisode, diskName, episodesByShow, result);
                }
            });
        }
    }

    private void addNextEpisodeMediaFiles(UUID showId, EpisodeEntity lastWatched, String diskName,
                                          Map<UUID, List<EpisodeEntity>> episodesByShow,
                                          PreTranscodeCollection result) {
        List<EpisodeEntity> showEpisodes = episodesByShow.computeIfAbsent(showId,
                id -> episodeRepository.findByShowEntityId(id,
                        Sort.by("seasonEntity.number").ascending().and(Sort.by("number").ascending())));
        for (int i = 0; i < showEpisodes.size(); i++) {
            if (showEpisodes.get(i).getId().equals(lastWatched.getId()) && i + 1 < showEpisodes.size()) {
                EpisodeEntity next = showEpisodes.get(i + 1);
                addMediaFiles(next.getMediaFileEntities(), diskName, result, next.getId(), null);
                break;
            }
        }
    }

    private void collectMovieMediaFiles(UUID userId, String diskName, PreTranscodeCollection result) {
        watchStatusRepository.findRecentMovieIdsByUserId(userId).forEach(movieIdStr -> {
            UUID movieId = UUID.fromString(movieIdStr);
            movieRepository.findById(movieId).ifPresent(movie ->
                    addMediaFiles(movie.getMediaFileEntities(), diskName, result, null, movieId));
        });
    }

    private static void addMediaFiles(List<MediaFileEntity> mediaFiles, String diskName,
                                      PreTranscodeCollection result, UUID episodeId, UUID movieId) {
        mediaFiles.stream()
                .filter(mf -> diskName.equals(mf.getDirectoryEntity().getName()))
                .forEach(mf -> {
                    if (mf.getMediaFileStreamEntity() == null || mf.getMediaFileStreamEntity().isEmpty()) {
                        result.unanalyzedFiles().add(new UnanalyzedMediaFile(
                                mf.getId(), mf.getDirectoryEntity().getId(), mf.getPath(), episodeId, movieId));
                    } else {
                        result.mediaFileIds().add(mf.getId());
                    }
                });
    }
}
