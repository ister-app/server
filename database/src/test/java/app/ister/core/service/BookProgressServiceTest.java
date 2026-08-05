package app.ister.core.service;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.repository.WatchStatusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookProgressServiceTest {

    @InjectMocks
    private BookProgressService subject;

    @Mock
    private WatchStatusRepository watchStatusRepository;

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final long HOUR = 3_600_000L;

    private final UserEntity user = user();
    private final BookEntity book = book();

    private static UserEntity user() {
        UserEntity user = UserEntity.builder().name("test-user").externalId("sub-123").build();
        user.setId(UUID.randomUUID());
        return user;
    }

    private static BookEntity book() {
        BookEntity book = BookEntity.builder().name("De wolven van Arazan").build();
        book.setId(UUID.randomUUID());
        return book;
    }

    /** A chapter row as the native query returns it; nulls mean "never played" / "not analysed". */
    private record Row(UUID bookId, UUID chapterId, Long duration, Boolean watched, Long progress,
                       Instant updated) implements WatchStatusRepository.ChapterProgressRow {

        @Override
        public UUID getBookId() {
            return bookId;
        }

        @Override
        public UUID getChapterId() {
            return chapterId;
        }

        @Override
        public Long getDurationInMilliseconds() {
            return duration;
        }

        @Override
        public Boolean getWatched() {
            return watched;
        }

        @Override
        public Long getProgressInMilliseconds() {
            return progress;
        }

        @Override
        public Instant getUpdatedAt() {
            return updated;
        }
    }

    private Row chapter(Long duration, Boolean watched, Long progress, Instant updated) {
        return new Row(book.getId(), UUID.randomUUID(), duration, watched, progress, updated);
    }

    private Row unplayed(Long duration) {
        return chapter(duration, null, null, null);
    }

    private void haveChapters(Row... rows) {
        when(watchStatusRepository.findChapterProgress(eq(user.getId()), anyList())).thenReturn(List.of(rows));
    }

    private void haveReadingRow(WatchStatusEntity... statuses) {
        when(watchStatusRepository.findByUserEntityExternalIdAndBookEntityIn(eq("sub-123"), anyList(), any(Sort.class)))
                .thenReturn(List.of(statuses));
    }

    private WatchStatusEntity reading(String location, Double progress, boolean watched, Instant updated) {
        WatchStatusEntity status = WatchStatusEntity.builder()
                .bookEntity(book)
                .readingLocation(location)
                .readingProgress(progress)
                .watched(watched)
                .build();
        status.setDateUpdated(updated);
        return status;
    }

    private BookProgressService.BookProgress progress() {
        Optional<BookProgressService.BookProgress> progress = subject.forBook(user, book);
        assertTrue(progress.isPresent());
        return progress.get();
    }

    /** The whole point: two finished hours plus half of the third is half the book, not half a chapter. */
    @Test
    void countsFinishedChaptersInFullTowardsTheWholeBook() {
        haveChapters(
                chapter(HOUR, true, HOUR, NOW.minus(2, ChronoUnit.DAYS)),
                chapter(HOUR, true, HOUR, NOW.minus(1, ChronoUnit.DAYS)),
                chapter(HOUR, false, HOUR / 2, NOW),
                unplayed(HOUR));
        haveReadingRow();

        BookProgressService.BookProgress progress = progress();

        assertEquals(BookProgressService.BookProgressMode.LISTENING, progress.mode());
        assertEquals(2.5 / 4, progress.progress(), 0.0001);
        assertEquals(4 * HOUR, progress.durationInMilliseconds());
        assertEquals(5 * HOUR / 2, progress.positionInMilliseconds());
        assertFalse(progress.finished());
        assertEquals(NOW, progress.updatedAt());
    }

    /** A chapter without a duration is left out of both sides of the fraction, not counted as zero. */
    @Test
    void ignoresChaptersThatWereNeverAnalysed() {
        haveChapters(
                chapter(HOUR, true, HOUR, NOW),
                unplayed(null),
                unplayed(HOUR));
        haveReadingRow();

        assertEquals(0.5, progress().progress(), 0.0001);
    }

    @Test
    void reportsAFinishedAudiobook() {
        haveChapters(
                chapter(HOUR, true, HOUR, NOW.minus(1, ChronoUnit.DAYS)),
                chapter(HOUR, true, HOUR, NOW));
        haveReadingRow();

        BookProgressService.BookProgress progress = progress();

        assertEquals(1.0, progress.progress(), 0.0001);
        assertTrue(progress.finished());
    }

    @Test
    void hasNoProgressForABookThatWasNeverStarted() {
        haveChapters(unplayed(HOUR), unplayed(HOUR));
        haveReadingRow(reading(null, null, false, NOW));

        assertTrue(subject.forBook(user, book).isEmpty());
    }

    /** No durations yet means no fraction to show — the chapters are still being analysed. */
    @Test
    void hasNoProgressWhileTheAudiobookHasNoDurations() {
        haveChapters(chapter(null, false, HOUR / 2, NOW));
        haveReadingRow();

        assertTrue(subject.forBook(user, book).isEmpty());
    }

    /** Early in an epub the fraction still rounds to zero; the saved position is what marks it started. */
    @Test
    void countsASavedReadingLocationWithoutAFraction() {
        haveChapters();
        haveReadingRow(reading("ister:v1;spine=2;block=3", null, false, NOW));

        BookProgressService.BookProgress progress = progress();

        assertEquals(BookProgressService.BookProgressMode.READING, progress.mode());
        assertEquals(0.0, progress.progress(), 0.0001);
    }

    @Test
    void readingWinsWhenItWasTouchedLast() {
        haveChapters(chapter(HOUR, false, HOUR / 4, NOW.minus(3, ChronoUnit.DAYS)));
        haveReadingRow(reading("ister:v1;spine=5;block=1", 0.6, false, NOW));

        BookProgressService.BookProgress progress = progress();

        assertEquals(BookProgressService.BookProgressMode.READING, progress.mode());
        assertEquals(0.6, progress.progress(), 0.0001);
    }

    @Test
    void listeningWinsWhenItWasTouchedLast() {
        haveChapters(
                chapter(HOUR, true, HOUR, NOW.minus(1, ChronoUnit.DAYS)),
                chapter(HOUR, false, HOUR / 2, NOW));
        haveReadingRow(reading("ister:v1;spine=5;block=1", 0.6, false, NOW.minus(3, ChronoUnit.DAYS)));

        BookProgressService.BookProgress progress = progress();

        assertEquals(BookProgressService.BookProgressMode.LISTENING, progress.mode());
        assertEquals(0.75, progress.progress(), 0.0001);
    }

    @Test
    void hasNoProgressForABookWithoutChaptersOrReadingRow() {
        haveChapters();
        haveReadingRow();

        assertTrue(subject.forBook(user, book).isEmpty());
    }
}
