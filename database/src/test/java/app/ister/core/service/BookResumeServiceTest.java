package app.ister.core.service;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.WatchStatusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookResumeServiceTest {

    @InjectMocks
    private BookResumeService subject;

    @Mock
    private ChapterRepository chapterRepository;

    @Mock
    private WatchStatusRepository watchStatusRepository;

    private final UserEntity user = user();
    private final BookEntity book = book();
    private final List<ChapterEntity> chapters = chapters(12);

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

    private List<ChapterEntity> chapters(int count) {
        List<ChapterEntity> chapters = new ArrayList<>();
        for (int number = 1; number <= count; number++) {
            ChapterEntity chapter = ChapterEntity.builder().number(number).bookEntity(book).build();
            chapter.setId(UUID.randomUUID());
            chapters.add(chapter);
        }
        return chapters;
    }

    private ChapterEntity chapter(int number) {
        return chapters.get(number - 1);
    }

    private WatchStatusEntity status(ChapterEntity chapter, boolean watched, Instant updated) {
        WatchStatusEntity status = WatchStatusEntity.builder()
                .chapterEntity(chapter)
                .watched(watched)
                .build();
        status.setDateUpdated(updated);
        return status;
    }

    /** The repository returns the statuses most recently updated first; mirror that in the stub. */
    private void haveListenedTo(WatchStatusEntity... statuses) {
        List<WatchStatusEntity> mostRecentFirst = new ArrayList<>(List.of(statuses));
        mostRecentFirst.sort(Comparator.comparing(WatchStatusEntity::getDateUpdated).reversed());
        when(chapterRepository.findByBookEntity_Id(eq(book.getId()), any(Sort.class))).thenReturn(chapters);
        when(watchStatusRepository.findByUserEntityExternalIdAndChapterEntityIn(eq("sub-123"), anyList(), any(Sort.class)))
                .thenReturn(mostRecentFirst);
    }

    /** Chapters skipped earlier in the book do not pull the listener back. */
    @Test
    void resumesAtTheChapterStillBeingListenedTo() {
        Instant now = Instant.now();
        haveListenedTo(
                status(chapter(1), true, now.minus(3, ChronoUnit.DAYS)),
                status(chapter(10), false, now));

        Optional<BookResumeService.ChapterResume> resume = subject.resume(user, book.getId());

        assertTrue(resume.isPresent());
        assertEquals(chapter(10), resume.get().chapter());
        assertEquals(now, resume.get().lastPlayed());
    }

    /** Finishing chapter 10 moves on to 11, even though 3 was never listened to. */
    @Test
    void resumesAfterTheFinishedChapterInsteadOfAnEarlierGap() {
        Instant now = Instant.now();
        haveListenedTo(
                status(chapter(2), true, now.minus(3, ChronoUnit.DAYS)),
                status(chapter(10), true, now));

        Optional<BookResumeService.ChapterResume> resume = subject.resume(user, book.getId());

        assertTrue(resume.isPresent());
        assertEquals(chapter(11), resume.get().chapter());
    }

    /** Chapters already finished after the last played one are skipped too. */
    @Test
    void skipsChaptersAlreadyFinishedAfterTheLastPlayedOne() {
        Instant now = Instant.now();
        haveListenedTo(
                status(chapter(11), true, now.minus(1, ChronoUnit.DAYS)),
                status(chapter(10), true, now));

        Optional<BookResumeService.ChapterResume> resume = subject.resume(user, book.getId());

        assertTrue(resume.isPresent());
        assertEquals(chapter(12), resume.get().chapter());
    }

    /** Never started: the caller starts at the first chapter itself. */
    @Test
    void hasNothingToResumeForAnUnstartedBook() {
        haveListenedTo();

        assertTrue(subject.resume(user, book.getId()).isEmpty());
    }

    /** Listened to the end: nothing left to continue with. */
    @Test
    void hasNothingToResumeAfterTheLastChapter() {
        haveListenedTo(status(chapter(12), true, Instant.now()));

        assertTrue(subject.resume(user, book.getId()).isEmpty());
    }

    @Test
    void hasNothingToResumeForABookWithoutChapters() {
        when(chapterRepository.findByBookEntity_Id(eq(book.getId()), any(Sort.class))).thenReturn(List.of());

        assertTrue(subject.resume(user, book.getId()).isEmpty());
    }
}
