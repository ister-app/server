package app.ister.core.service;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.UserRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.UserSettingsService.UserSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PreTranscodeService {

    private final UserRepository userRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final EpisodeRepository episodeRepository;
    private final MovieRepository movieRepository;
    private final UserSettingsService userSettingsService;

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
     * A media file to pre-transcode, with the audio languages and video quality the interested
     * users actually need. Transcoding every audio stream of a file in every bitrate is what made a
     * seven-language episode take days of background passes.
     *
     * @param audioLanguages audio languages worth transcoding, merged across the interested users
     * @param maxVideoHeight highest video variant to produce, or null when a user wants them all
     */
    public record PreTranscodeTarget(UUID mediaFileId, Set<String> audioLanguages, Integer maxVideoHeight) {
    }

    /**
     * @param targets         media files ready for pre-transcoding, deduplicated per media file
     * @param unanalyzedFiles media files that first need (re-)analysis before they can be transcoded
     */
    public record PreTranscodeCollection(Set<PreTranscodeTarget> targets, Set<UnanalyzedMediaFile> unanalyzedFiles) {

        /** The media files to pre-transcode, without the per-user wishes attached to them. */
        public Set<UUID> mediaFileIds() {
            return targets.stream()
                    .map(PreTranscodeTarget::mediaFileId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    /** Accumulates the wishes of every user interested in one media file. */
    private static final class TargetAccumulator {
        private final Set<String> audioLanguages = new LinkedHashSet<>();
        private Integer maxVideoHeight;
        private boolean uncappedVideo;

        void add(UserSettings settings) {
            audioLanguages.addAll(settings.preferredAudioLanguages());
            if (settings.maxVideoHeight() == null) {
                uncappedVideo = true;
            } else if (maxVideoHeight == null || settings.maxVideoHeight() > maxVideoHeight) {
                maxVideoHeight = settings.maxVideoHeight();
            }
        }

        PreTranscodeTarget toTarget(UUID mediaFileId) {
            return new PreTranscodeTarget(mediaFileId, Set.copyOf(audioLanguages),
                    uncappedVideo ? null : maxVideoHeight);
        }
    }

    /**
     * Collects the media files to pre-transcode for the given disk, across all users.
     * <p>
     * Episodes: a recently-watched episode is included only while it is not fully watched
     * (resuming a half-watched episode stays instant); if it was watched within the last
     * {@code nextEpisodeRecentDays} days, the next episode in show order is also included.
     * Movies: only recently-watched movies that are not fully watched are included.
     * Each file is tagged with the audio languages and video quality of the users that pulled it in.
     * Media files without analyzed streams cannot be transcoded and are returned separately
     * so the caller can trigger (re-)analysis.
     * All DB access is performed within this transaction to avoid LazyInitializationException.
     *
     * @param diskName the directory name to filter media files by
     */
    @Transactional(readOnly = true)
    public PreTranscodeCollection collectMediaFilesToPreTranscode(String diskName) {
        Map<UUID, List<EpisodeEntity>> episodesByShow = new HashMap<>();
        Map<UUID, TargetAccumulator> targetsByMediaFile = new LinkedHashMap<>();
        Set<UnanalyzedMediaFile> unanalyzedFiles = new LinkedHashSet<>();
        Instant recentCutoff = Instant.now().minus(Duration.ofDays(nextEpisodeRecentDays));

        userRepository.findAll().forEach(user -> {
            UserSettings settings = userSettingsService.forUser(user.getId());
            collectEpisodeMediaFiles(user.getId(), settings, diskName, recentCutoff, episodesByShow,
                    targetsByMediaFile, unanalyzedFiles);
            collectMovieMediaFiles(user.getId(), settings, diskName, targetsByMediaFile, unanalyzedFiles);
        });

        Set<PreTranscodeTarget> targets = new LinkedHashSet<>();
        targetsByMediaFile.forEach((mediaFileId, accumulator) -> targets.add(accumulator.toTarget(mediaFileId)));

        log.debug("Collected {} media files ({} unanalyzed) to pre-transcode for disk: {}",
                targets.size(), unanalyzedFiles.size(), diskName);
        return new PreTranscodeCollection(targets, unanalyzedFiles);
    }

    private void collectEpisodeMediaFiles(UUID userId, UserSettings settings, String diskName, Instant recentCutoff,
                                          Map<UUID, List<EpisodeEntity>> episodesByShow,
                                          Map<UUID, TargetAccumulator> targetsByMediaFile,
                                          Set<UnanalyzedMediaFile> unanalyzedFiles) {
        List<Object[]> rows = watchStatusRepository.findRecentEpisodesWithDateByUserId(userId);
        for (Object[] row : rows) {
            UUID episodeId = UUID.fromString(row[0].toString());
            UUID showId = UUID.fromString(row[1].toString());
            Instant lastWatched = (Instant) row[2];
            boolean watched = Boolean.TRUE.equals(row[3]);
            episodeRepository.findById(episodeId).ifPresent(lastWatchedEpisode -> {
                if (!watched) {
                    // Only a half-watched episode itself is kept warm; a finished episode
                    // needs no cache anymore, just its successor below.
                    addMediaFiles(lastWatchedEpisode.getMediaFileEntities(), settings, diskName,
                            targetsByMediaFile, unanalyzedFiles, lastWatchedEpisode.getId(), null);
                }
                if (lastWatched.isAfter(recentCutoff)) {
                    addNextEpisodeMediaFiles(showId, lastWatchedEpisode, settings, diskName, episodesByShow,
                            targetsByMediaFile, unanalyzedFiles);
                }
            });
        }
    }

    private void addNextEpisodeMediaFiles(UUID showId, EpisodeEntity lastWatched, UserSettings settings, String diskName,
                                          Map<UUID, List<EpisodeEntity>> episodesByShow,
                                          Map<UUID, TargetAccumulator> targetsByMediaFile,
                                          Set<UnanalyzedMediaFile> unanalyzedFiles) {
        List<EpisodeEntity> showEpisodes = episodesByShow.computeIfAbsent(showId,
                id -> episodeRepository.findByShowEntityId(id,
                        Sort.by("seasonEntity.number").ascending().and(Sort.by("number").ascending())));
        for (int i = 0; i < showEpisodes.size(); i++) {
            if (showEpisodes.get(i).getId().equals(lastWatched.getId()) && i + 1 < showEpisodes.size()) {
                EpisodeEntity next = showEpisodes.get(i + 1);
                addMediaFiles(next.getMediaFileEntities(), settings, diskName, targetsByMediaFile,
                        unanalyzedFiles, next.getId(), null);
                break;
            }
        }
    }

    private void collectMovieMediaFiles(UUID userId, UserSettings settings, String diskName,
                                        Map<UUID, TargetAccumulator> targetsByMediaFile,
                                        Set<UnanalyzedMediaFile> unanalyzedFiles) {
        watchStatusRepository.findRecentUnwatchedMovieIdsByUserId(userId).forEach(movieIdStr -> {
            UUID movieId = UUID.fromString(movieIdStr);
            movieRepository.findById(movieId).ifPresent(movie ->
                    addMediaFiles(movie.getMediaFileEntities(), settings, diskName, targetsByMediaFile,
                            unanalyzedFiles, null, movieId));
        });
    }

    private static void addMediaFiles(List<MediaFileEntity> mediaFiles, UserSettings settings, String diskName,
                                      Map<UUID, TargetAccumulator> targetsByMediaFile,
                                      Set<UnanalyzedMediaFile> unanalyzedFiles, UUID episodeId, UUID movieId) {
        mediaFiles.stream()
                .filter(mf -> diskName.equals(mf.getDirectoryEntity().getName()))
                .forEach(mf -> {
                    if (mf.getMediaFileStreamEntity() == null || mf.getMediaFileStreamEntity().isEmpty()) {
                        unanalyzedFiles.add(new UnanalyzedMediaFile(
                                mf.getId(), mf.getDirectoryEntity().getId(), mf.getPath(), episodeId, movieId));
                    } else {
                        targetsByMediaFile.computeIfAbsent(mf.getId(), id -> new TargetAccumulator()).add(settings);
                    }
                });
    }
}
