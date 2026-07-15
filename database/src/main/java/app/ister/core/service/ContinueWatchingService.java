package app.ister.core.service;

import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.ContinueWatchingEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.MediaType;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.ContinueWatchingRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.repository.WatchStatusRepository.RecentEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Maintains the precomputed continue-watching list ({@link ContinueWatchingEntity}).
 *
 * <p>Every entry answers one question: given what the user last played in this show / movie / book /
 * podcast, what should they resume with? An unfinished item resumes itself; a finished one hands
 * over to the next episode or chapter. When there is no successor the entry keeps its place in the
 * history with no target, so a later scanned episode can put the show back in the list.
 *
 * <p>Two ways in: {@link #onWatchStatusChanged} updates one entry from a playback heartbeat (a
 * couple of indexed queries), and {@link #rebuildForUser} recomputes a user's whole list from the
 * watch history. The rebuild runs nightly, which bounds how long any drift — an episode deleted, a
 * watch row edited outside the heartbeat — can survive.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContinueWatchingService {

    /** Season/episode numbers start at 0 (specials), so this is "before the first episode". */
    private static final int BEFORE_FIRST = -1;

    private final ContinueWatchingRepository continueWatchingRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final EpisodeRepository episodeRepository;
    private final ChapterRepository chapterRepository;
    private final PodcastEpisodeRepository podcastEpisodeRepository;

    /** How far back the continue-watching list looks; older entries are dropped on rebuild. */
    @Value("${app.ister.server.continue-watching.history-days:150}")
    private int historyDays;

    /** The user's list, newest first. Entries with nothing left to resume are left out. */
    @Transactional(readOnly = true)
    public List<ContinueWatchingEntity> entriesFor(UUID userId) {
        return continueWatchingRepository.findEntries(userId, cutoff());
    }

    /**
     * Updates the one entry the given watch status belongs to. Called from the playback heartbeat,
     * inside its transaction: the cache and the watch status it is derived from commit together.
     */
    @Transactional
    public void onWatchStatusChanged(WatchStatusEntity status) {
        UserEntity user = status.getUserEntity();
        Instant lastWatched = Instant.now();

        if (status.getEpisodeEntity() != null) {
            EpisodeEntity episode = status.getEpisodeEntity();
            UUID target = status.isWatched()
                    ? nextUnwatchedEpisode(episode.getShowEntity().getId(), user.getId(),
                            episode.getSeasonEntity().getNumber(), episode.getNumber()).orElse(null)
                    : episode.getId();
            upsertEpisode(user, episode.getShowEntity().getId(), target, lastWatched);
        } else if (status.getChapterEntity() != null) {
            ChapterEntity chapter = status.getChapterEntity();
            UUID bookId = chapter.getBookEntity().getId();
            UUID target = status.isWatched()
                    ? nextUnfinishedChapter(bookId, user.getId(), chapter.getNumber()).orElse(null)
                    : chapter.getId();
            upsertChapter(user, bookId, target, lastWatched);
        } else if (status.getMovieEntity() != null) {
            UUID movieId = status.getMovieEntity().getId();
            upsertMovie(user, movieId, status.isWatched() ? null : movieId, lastWatched);
        } else if (status.getBookEntity() != null) {
            UUID bookId = status.getBookEntity().getId();
            upsertBook(user, bookId, startedReading(status) ? bookId : null, lastWatched);
        } else if (status.getPodcastEpisodeEntity() != null) {
            var episode = status.getPodcastEpisodeEntity();
            UUID podcastId = episode.getPodcastEntity().getId();
            if (status.isWatched()) {
                UUID target = nextUnfinishedPodcastEpisode(podcastId, user.getId(), episode.getId()).orElse(null);
                upsertPodcastEpisode(user, podcastId, target, lastWatched);
            } else if (startedListening(status)) {
                upsertPodcastEpisode(user, podcastId, episode.getId(), lastWatched);
            }
            // Not started (progress 0) and not watched: nothing to resume yet. Since the entry is now
            // keyed by podcast, writing a null target here would clobber another episode the user is
            // mid-way through, so leave any existing entry untouched.
        }
    }

    /**
     * Recomputes a user's entire list from their watch history. Also the backfill after the table
     * was introduced, and the repair for entries that drifted: the old rows are thrown away, so
     * entries whose media no longer exists disappear here.
     */
    @Transactional
    public void rebuildForUser(UserEntity user) {
        UUID userId = user.getId();
        Instant cutoff = cutoff();
        continueWatchingRepository.deleteByUserEntityId(userId);

        for (RecentEntry entry : watchStatusRepository.findRecentEpisodeEntries(userId, cutoff)) {
            upsertEpisode(user, entry.getGroupId(), episodeTarget(userId, entry), entry.getLastWatched());
        }
        for (RecentEntry entry : watchStatusRepository.findRecentChapterEntries(userId, cutoff)) {
            upsertChapter(user, entry.getGroupId(), chapterTarget(userId, entry), entry.getLastWatched());
        }
        for (RecentEntry entry : watchStatusRepository.findRecentMovieEntries(userId, cutoff)) {
            upsertMovie(user, entry.getGroupId(), entry.getWatched() ? null : entry.getItemId(), entry.getLastWatched());
        }
        for (RecentEntry entry : watchStatusRepository.findRecentBookEntries(userId, cutoff)) {
            boolean started = !entry.getWatched() && entry.getReadingProgress() != null && entry.getReadingProgress() > 0;
            upsertBook(user, entry.getGroupId(), started ? entry.getItemId() : null, entry.getLastWatched());
        }
        for (RecentEntry entry : watchStatusRepository.findRecentPodcastEpisodeEntries(userId, cutoff)) {
            upsertPodcastEpisode(user, entry.getGroupId(), podcastTarget(userId, entry), entry.getLastWatched());
        }
        log.debug("Rebuilt continue watching for user: {}", userId);
    }

    /**
     * Re-evaluates the shows that had run out of episodes to continue with. Called when the scanner
     * adds an episode: without it, a show the user had finished would only reappear in their list
     * after the nightly rebuild.
     */
    @Transactional
    public void recomputeForShow(UUID showId) {
        for (ContinueWatchingEntity entry : continueWatchingRepository.findExhaustedShowEntries(showId)) {
            UUID userId = entry.getUserEntity().getId();
            firstUnwatchedEpisode(showId, userId).ifPresent(episodeId -> {
                entry.setEpisodeEntity(episodeRepository.getReferenceById(episodeId));
                continueWatchingRepository.save(entry);
                log.debug("Show {} continues for user {} with new episode {}", showId, userId, episodeId);
            });
        }
    }

    /** The audiobook twin of {@link #recomputeForShow}. */
    @Transactional
    public void recomputeForBook(UUID bookId) {
        for (ContinueWatchingEntity entry : continueWatchingRepository.findExhaustedBookEntries(bookId)) {
            UUID userId = entry.getUserEntity().getId();
            firstUnfinishedChapter(bookId, userId).ifPresent(chapterId -> {
                entry.setChapterEntity(chapterRepository.getReferenceById(chapterId));
                continueWatchingRepository.save(entry);
                log.debug("Book {} continues for user {} with new chapter {}", bookId, userId, chapterId);
            });
        }
    }

    private UUID episodeTarget(UUID userId, RecentEntry entry) {
        if (!entry.getWatched()) {
            return entry.getItemId();
        }
        return episodeRepository.findById(entry.getItemId())
                .flatMap(episode -> nextUnwatchedEpisode(entry.getGroupId(), userId,
                        episode.getSeasonEntity().getNumber(), episode.getNumber()))
                .orElse(null);
    }

    private UUID chapterTarget(UUID userId, RecentEntry entry) {
        if (!entry.getWatched()) {
            return entry.getItemId();
        }
        return chapterRepository.findById(entry.getItemId())
                .flatMap(chapter -> nextUnfinishedChapter(entry.getGroupId(), userId, chapter.getNumber()))
                .orElse(null);
    }

    private UUID podcastTarget(UUID userId, RecentEntry entry) {
        if (entry.getWatched()) {
            return nextUnfinishedPodcastEpisode(entry.getGroupId(), userId, entry.getItemId()).orElse(null);
        }
        return entry.getProgressInMilliseconds() > 0 ? entry.getItemId() : null;
    }

    private Optional<UUID> nextUnfinishedPodcastEpisode(UUID podcastId, UUID userId, UUID afterEpisodeId) {
        return podcastEpisodeRepository.findNextUnfinishedPodcastEpisodeId(podcastId, userId, afterEpisodeId)
                .stream().findFirst();
    }

    private Optional<UUID> nextUnwatchedEpisode(UUID showId, UUID userId, int afterSeason, int afterEpisode) {
        return episodeRepository.findNextUnwatchedEpisodeId(showId, userId, afterSeason, afterEpisode)
                .stream().findFirst();
    }

    private Optional<UUID> firstUnwatchedEpisode(UUID showId, UUID userId) {
        return nextUnwatchedEpisode(showId, userId, BEFORE_FIRST, BEFORE_FIRST);
    }

    private Optional<UUID> nextUnfinishedChapter(UUID bookId, UUID userId, int afterNumber) {
        return chapterRepository.findNextUnfinishedChapterId(bookId, userId, afterNumber)
                .stream().findFirst();
    }

    private Optional<UUID> firstUnfinishedChapter(UUID bookId, UUID userId) {
        return nextUnfinishedChapter(bookId, userId, BEFORE_FIRST);
    }

    /** An epub only counts as "continue reading" once the user actually got somewhere in it. */
    private static boolean startedReading(WatchStatusEntity status) {
        return !status.isWatched() && status.getReadingProgress() != null && status.getReadingProgress() > 0;
    }

    private static boolean startedListening(WatchStatusEntity status) {
        return !status.isWatched() && status.getProgressInMilliseconds() > 0;
    }

    private void upsertEpisode(UserEntity user, UUID showId, UUID episodeId, Instant lastWatched) {
        continueWatchingRepository.upsert(user.getId(), MediaType.EPISODE.name(), showId,
                episodeId, null, null, null, null, lastWatched);
    }

    private void upsertChapter(UserEntity user, UUID bookId, UUID chapterId, Instant lastWatched) {
        continueWatchingRepository.upsert(user.getId(), MediaType.CHAPTER.name(), bookId,
                null, null, chapterId, null, null, lastWatched);
    }

    private void upsertMovie(UserEntity user, UUID groupId, UUID movieId, Instant lastWatched) {
        continueWatchingRepository.upsert(user.getId(), MediaType.MOVIE.name(), groupId,
                null, movieId, null, null, null, lastWatched);
    }

    private void upsertBook(UserEntity user, UUID groupId, UUID bookId, Instant lastWatched) {
        continueWatchingRepository.upsert(user.getId(), MediaType.BOOK.name(), groupId,
                null, null, null, bookId, null, lastWatched);
    }

    private void upsertPodcastEpisode(UserEntity user, UUID groupId, UUID podcastEpisodeId, Instant lastWatched) {
        continueWatchingRepository.upsert(user.getId(), MediaType.PODCAST_EPISODE.name(), groupId,
                null, null, null, null, podcastEpisodeId, lastWatched);
    }

    private Instant cutoff() {
        return Instant.now().minus(Duration.ofDays(historyDays));
    }
}
