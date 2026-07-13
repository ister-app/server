package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.UserService;
import app.ister.core.service.WatchStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReadingProgressControllerTest {

    @InjectMocks
    private ReadingProgressController subject;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private ChapterRepository chapterRepository;

    @Mock
    private MediaFileRepository mediaFileRepository;

    @Mock
    private WatchStatusRepository watchStatusRepository;

    @Mock
    private WatchStatusService watchStatusService;

    @Mock
    private UserService userService;

    @Mock
    private Authentication authentication;

    private BookEntity book;
    private List<ChapterEntity> chapters;
    private final Map<UUID, WatchStatusEntity> chapterStatuses = new HashMap<>();

    @BeforeEach
    void setUp() {
        chapters = new ArrayList<>();
        book = BookEntity.builder().chapterEntities(chapters).build();
        book.setId(UUID.randomUUID());
        for (int number = 1; number <= 3; number++) {
            ChapterEntity chapter = ChapterEntity.builder().number(number).bookEntity(book).build();
            chapter.setId(UUID.randomUUID());
            chapters.add(chapter);
            chapterStatuses.put(chapter.getId(),
                    WatchStatusEntity.builder().chapterEntity(chapter).watched(false).build());
        }

        when(bookRepository.findById(book.getId())).thenReturn(Optional.of(book));
        when(watchStatusService.getOrCreateForBook(any(), any()))
                .thenReturn(WatchStatusEntity.builder().bookEntity(book).watched(false).build());
        when(watchStatusService.getOrCreateForChapter(any(), any(ChapterEntity.class)))
                .thenAnswer(call -> chapterStatuses.get(((ChapterEntity) call.getArgument(1)).getId()));
        chapters.forEach(chapter ->
                when(chapterRepository.findById(chapter.getId())).thenReturn(Optional.of(chapter)));
    }

    /**
     * Reading into the middle of the book has to leave the audiobook resumable at the same place:
     * the chapter being read carries the derived position, the ones before it count as listened.
     */
    @Test
    void readingPositionIsMirroredOntoTheAudiobook() {
        ChapterEntity current = chapters.get(1);

        subject.updateReadingProgress(new ReadingProgressController.ReadingProgressRequest(
                book.getId(), "epubcfi(/6/4!/4/2)", 0.4, current.getId(), 90_000L, UUID.randomUUID()),
                authentication);

        assertTrue(chapterStatuses.get(chapters.get(0).getId()).isWatched());

        WatchStatusEntity currentStatus = chapterStatuses.get(current.getId());
        assertFalse(currentStatus.isWatched());
        assertEquals(90_000L, currentStatus.getProgressInMilliseconds());

        WatchStatusEntity later = chapterStatuses.get(chapters.get(2).getId());
        assertFalse(later.isWatched());
        assertEquals(0L, later.getProgressInMilliseconds());
    }

    /** A book without an audiobook still stores its reading position. */
    @Test
    void readingPositionWithoutChapterOnlyStoresReadingProgress() {
        ReadingProgressController.ReadingProgressResponse response = subject.updateReadingProgress(
                new ReadingProgressController.ReadingProgressRequest(
                        book.getId(), "epubcfi(/6/4!/4/2)", 0.4, null, null, null),
                authentication);

        assertEquals(0.4, response.progress());
        assertFalse(response.finished());
        chapterStatuses.values().forEach(status -> assertEquals(0L, status.getProgressInMilliseconds()));
    }
}
