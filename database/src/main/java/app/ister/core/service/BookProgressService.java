package app.ister.core.service;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.repository.WatchStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a user stands in a book <em>as a whole</em>, whether they read it or listen to it.
 *
 * <p>Reading progress is already book-wide ({@link WatchStatusEntity#getReadingProgress()});
 * listening progress is not — it is stored per chapter, so a listener halfway through chapter 12 of
 * 30 would otherwise look like they just started. This service adds the finished chapters up: a
 * chapter counts for its full duration once it is watched, for its stored position while it is
 * playing, and not at all when its duration is unknown (a half-analysed book still gets a sensible
 * fraction out of the chapters that do have one).
 *
 * <p>Someone who both reads and listens to the same book gets the mode they touched last, so the
 * bar follows them between the epub and the audiobook instead of freezing on one of the two.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookProgressService {
    private final WatchStatusRepository watchStatusRepository;

    public enum BookProgressMode { READING, LISTENING }

    /**
     * @param progress                0.0–1.0 over the whole book
     * @param durationInMilliseconds  total audiobook duration, null when reading or unknown
     * @param positionInMilliseconds  position in that total, null when reading or unknown
     */
    public record BookProgress(BookProgressMode mode, double progress, boolean finished,
                               Long durationInMilliseconds, Long positionInMilliseconds,
                               Instant updatedAt) {
    }

    /** Empty when the user never started this book in either form. */
    @Transactional(readOnly = true)
    public Optional<BookProgress> forBook(UserEntity userEntity, BookEntity bookEntity) {
        return Optional.ofNullable(progressByBookId(userEntity, List.of(bookEntity)).get(bookEntity.getId()));
    }

    /**
     * Keyed by book id, with books the user never started left out entirely — two queries for the
     * whole batch, so a continue-watching carousel costs no more than a single book page.
     */
    @Transactional(readOnly = true)
    public Map<UUID, BookProgress> forBooks(UserEntity userEntity, List<BookEntity> books) {
        return progressByBookId(userEntity, books);
    }

    private Map<UUID, BookProgress> progressByBookId(UserEntity userEntity, List<BookEntity> books) {
        if (books.isEmpty()) {
            return Map.of();
        }
        Map<UUID, BookProgress> listening = listeningProgress(userEntity, books);
        Map<UUID, BookProgress> reading = readingProgress(userEntity, books);

        Map<UUID, BookProgress> progressByBookId = new HashMap<>(listening);
        reading.forEach((bookId, read) -> progressByBookId.merge(bookId, read, BookProgressService::mostRecent));
        return progressByBookId;
    }

    /** Both modes started: the one the user touched last is the one they are in. */
    private static BookProgress mostRecent(BookProgress left, BookProgress right) {
        return right.updatedAt().isAfter(left.updatedAt()) ? right : left;
    }

    private Map<UUID, BookProgress> listeningProgress(UserEntity userEntity, List<BookEntity> books) {
        List<UUID> bookIds = books.stream().map(BookEntity::getId).toList();
        Map<UUID, ListeningTally> tallyByBookId = new HashMap<>();
        for (WatchStatusRepository.ChapterProgressRow row
                : watchStatusRepository.findChapterProgress(userEntity.getId(), bookIds)) {
            tallyByBookId.computeIfAbsent(row.getBookId(), id -> new ListeningTally()).add(row);
        }

        Map<UUID, BookProgress> progressByBookId = new HashMap<>();
        tallyByBookId.forEach((bookId, tally) -> tally.toProgress()
                .ifPresent(progress -> progressByBookId.put(bookId, progress)));
        return progressByBookId;
    }

    private Map<UUID, BookProgress> readingProgress(UserEntity userEntity, List<BookEntity> books) {
        Map<UUID, BookProgress> progressByBookId = new HashMap<>();
        List<WatchStatusEntity> statuses = watchStatusRepository.findByUserEntityExternalIdAndBookEntityIn(
                userEntity.getExternalId(), books, Sort.by("dateUpdated").descending());
        for (WatchStatusEntity status : statuses) {
            UUID bookId = status.getBookEntity().getId();
            if (progressByBookId.containsKey(bookId) || !hasStartedReading(status)) {
                continue;
            }
            double progress = status.getReadingProgress() != null ? status.getReadingProgress() : 0.0;
            progressByBookId.put(bookId, new BookProgress(BookProgressMode.READING,
                    clamp(progress), status.isWatched(), null, null, updatedAt(status.getDateUpdated())));
        }
        return progressByBookId;
    }

    /**
     * A saved position counts even without a fraction: early in a book the fraction still rounds to
     * zero, and a location written before the reader reported one has none at all. An empty row —
     * created by simply opening the book — does not.
     */
    private static boolean hasStartedReading(WatchStatusEntity status) {
        return status.getReadingLocation() != null || status.getReadingProgress() != null || status.isWatched();
    }

    private static double clamp(double progress) {
        return Math.clamp(progress, 0.0, 1.0);
    }

    private static Instant updatedAt(Instant dateUpdated) {
        return dateUpdated != null ? dateUpdated : Instant.EPOCH;
    }

    /** Running totals over the chapters of one book. */
    private static final class ListeningTally {
        private long durationInMilliseconds;
        private long positionInMilliseconds;
        private boolean started;
        private boolean allChaptersWatched = true;
        private Instant updatedAt;

        private void add(WatchStatusRepository.ChapterProgressRow row) {
            boolean watched = Boolean.TRUE.equals(row.getWatched());
            long progress = row.getProgressInMilliseconds() != null ? row.getProgressInMilliseconds() : 0L;
            if (watched || progress > 0) {
                started = true;
                Instant rowUpdated = updatedAt(row.getUpdatedAt());
                if (updatedAt == null || rowUpdated.isAfter(updatedAt)) {
                    updatedAt = rowUpdated;
                }
            }
            if (!watched) {
                allChaptersWatched = false;
            }
            Long duration = row.getDurationInMilliseconds();
            if (duration == null || duration <= 0) {
                // Not analysed yet: counting it in the denominator only would drag the bar down.
                return;
            }
            durationInMilliseconds += duration;
            positionInMilliseconds += watched ? duration : Math.min(progress, duration);
        }

        private Optional<BookProgress> toProgress() {
            if (!started || durationInMilliseconds <= 0) {
                return Optional.empty();
            }
            double progress = (double) positionInMilliseconds / durationInMilliseconds;
            return Optional.of(new BookProgress(BookProgressMode.LISTENING, clamp(progress),
                    allChaptersWatched, durationInMilliseconds, positionInMilliseconds, updatedAt));
        }
    }
}
