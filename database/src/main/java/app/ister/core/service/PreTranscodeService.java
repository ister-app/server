package app.ister.core.service;

import app.ister.core.entity.ContinueWatchingEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.UserRepository;
import app.ister.core.service.UserSettingsService.UserSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final EpisodeRepository episodeRepository;
    private final ContinueWatchingService continueWatchingService;
    private final UserSettingsService userSettingsService;

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
     * The work list is the users' continue-watching entries: exactly what they are about to play —
     * the episode or movie they left unfinished, or the next episode of a show they finished one of.
     * For episodes the one after it is kept warm too, so that autoplay does not stall at the end.
     * Each file is tagged with the audio languages and video quality of the users that pulled it in.
     * Media files without analyzed streams cannot be transcoded and are returned separately
     * so the caller can trigger (re-)analysis.
     * All DB access is performed within this transaction to avoid LazyInitializationException.
     *
     * @param diskName the directory name to filter media files by
     */
    @Transactional(readOnly = true)
    public PreTranscodeCollection collectMediaFilesToPreTranscode(String diskName) {
        Map<UUID, TargetAccumulator> targetsByMediaFile = new LinkedHashMap<>();
        Set<UnanalyzedMediaFile> unanalyzedFiles = new LinkedHashSet<>();

        userRepository.findAll().forEach(user -> {
            UserSettings settings = userSettingsService.forUser(user.getId());
            for (ContinueWatchingEntity entry : continueWatchingService.entriesFor(user.getId())) {
                switch (entry.getEntryType()) {
                    case EPISODE -> collectEpisodeMediaFiles(entry.getEpisodeEntity(), settings, diskName,
                            targetsByMediaFile, unanalyzedFiles);
                    case MOVIE -> collectMovieMediaFiles(entry.getMovieEntity(), settings, diskName,
                            targetsByMediaFile, unanalyzedFiles);
                    // Chapters, books and podcasts stream as audio; they need no video passes.
                    default -> log.trace("Not pre-transcoding {} entry {}", entry.getEntryType(), entry.getId());
                }
            }
        });

        Set<PreTranscodeTarget> targets = new LinkedHashSet<>();
        targetsByMediaFile.forEach((mediaFileId, accumulator) -> targets.add(accumulator.toTarget(mediaFileId)));

        log.debug("Collected {} media files ({} unanalyzed) to pre-transcode for disk: {}",
                targets.size(), unanalyzedFiles.size(), diskName);
        return new PreTranscodeCollection(targets, unanalyzedFiles);
    }

    private void collectEpisodeMediaFiles(EpisodeEntity episode, UserSettings settings, String diskName,
                                          Map<UUID, TargetAccumulator> targetsByMediaFile,
                                          Set<UnanalyzedMediaFile> unanalyzedFiles) {
        if (episode == null) {
            return;
        }
        addMediaFiles(episode.getMediaFileEntities(), settings, diskName, targetsByMediaFile,
                unanalyzedFiles, episode.getId(), null);

        episodeRepository.findNextEpisodeId(episode.getShowEntity().getId(),
                        episode.getSeasonEntity().getNumber(), episode.getNumber())
                .stream().findFirst()
                .flatMap(episodeRepository::findById)
                .ifPresent(next -> addMediaFiles(next.getMediaFileEntities(), settings, diskName,
                        targetsByMediaFile, unanalyzedFiles, next.getId(), null));
    }

    private void collectMovieMediaFiles(MovieEntity movie, UserSettings settings, String diskName,
                                        Map<UUID, TargetAccumulator> targetsByMediaFile,
                                        Set<UnanalyzedMediaFile> unanalyzedFiles) {
        if (movie == null) {
            return;
        }
        addMediaFiles(movie.getMediaFileEntities(), settings, diskName, targetsByMediaFile,
                unanalyzedFiles, null, movie.getId());
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
