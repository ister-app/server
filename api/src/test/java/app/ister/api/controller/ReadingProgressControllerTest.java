package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.ContinueWatchingService;
import app.ister.core.service.UserService;
import app.ister.core.service.WatchStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReadingProgressControllerTest {

    @Mock
    private app.ister.core.service.LibraryAccessService libraryAccessService;

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
    private ContinueWatchingService continueWatchingService;

    @Mock
    private UserService userService;

    @Mock
    private Authentication authentication;

    private BookEntity book;
    private List<ChapterEntity> chapters;
    private final Map<UUID, WatchStatusEntity> chapterStatuses = new HashMap<>();

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(libraryAccessService.canAccess(
                org.mockito.ArgumentMatchers.<app.ister.core.entity.LibraryEntity>any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        chapters = new ArrayList<>();
        book = BookEntity.builder().chapterEntities(chapters).build();
        book.setId(UUID.randomUUID());
        for (int number = 1; number <= 3; number++) {
            ChapterEntity chapter = ChapterEntity.builder().number(number).bookEntity(book)
                    .mediaFileEntities(new ArrayList<>()).build();
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

    /**
     * The current chapter must be flushed after the others so its {@code dateUpdated} is the newest
     * of the batch; BookResumeService resumes at the most recently updated chapter, so this is what
     * makes reading up to a chapter and then listening resume at that chapter rather than a later one.
     */
    @Test
    void theCurrentChapterIsWrittenLastSoListeningResumesThere() {
        ChapterEntity current = chapters.get(1);

        subject.updateReadingProgress(new ReadingProgressController.ReadingProgressRequest(
                book.getId(), "epubcfi(/6/4!/4/2)", 0.4, current.getId(), 90_000L, UUID.randomUUID()),
                authentication);

        InOrder inOrder = inOrder(watchStatusRepository);
        inOrder.verify(watchStatusRepository).flush();
        inOrder.verify(watchStatusRepository).saveAndFlush(chapterStatuses.get(current.getId()));
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

    /** At the end of the epub the book counts as read, and the stored progress never exceeds 1.0. */
    @Test
    void finishingTheEpubMarksTheBookAsRead() {
        ReadingProgressController.ReadingProgressResponse response = subject.updateReadingProgress(
                new ReadingProgressController.ReadingProgressRequest(
                        book.getId(), "epubcfi(/6/40)", 1.4, null, null, null),
                authentication);

        assertEquals(1.0, response.progress());
        assertTrue(response.finished());
    }

    /** A chapter id from another book would corrupt this book's listening state, so it is ignored. */
    @Test
    void aChapterFromAnotherBookIsIgnored() {
        BookEntity otherBook = BookEntity.builder().chapterEntities(new ArrayList<>()).build();
        otherBook.setId(UUID.randomUUID());
        ChapterEntity foreign = ChapterEntity.builder().number(1).bookEntity(otherBook).build();
        foreign.setId(UUID.randomUUID());
        when(chapterRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        subject.updateReadingProgress(new ReadingProgressController.ReadingProgressRequest(
                book.getId(), "epubcfi(/6/4)", 0.4, foreign.getId(), 90_000L, null), authentication);

        chapterStatuses.values().forEach(status -> assertEquals(0L, status.getProgressInMilliseconds()));
        verify(watchStatusService, never()).getOrCreateForChapter(any(), any(ChapterEntity.class));
    }

    @Test
    void anUnknownChapterIsIgnored() {
        UUID unknown = UUID.randomUUID();
        when(chapterRepository.findById(unknown)).thenReturn(Optional.empty());

        subject.updateReadingProgress(new ReadingProgressController.ReadingProgressRequest(
                book.getId(), "epubcfi(/6/4)", 0.4, unknown, 90_000L, null), authentication);

        verify(watchStatusService, never()).getOrCreateForChapter(any(), any(ChapterEntity.class));
    }

    @Test
    void getReadingProgressReturnsTheStoredPosition() {
        WatchStatusEntity stored = WatchStatusEntity.builder().bookEntity(book).build();
        stored.setReadingLocation("epubcfi(/6/8)");
        stored.setReadingProgress(0.25);
        when(watchStatusService.getOrCreateForBook(authentication, book)).thenReturn(stored);

        ResponseEntity<ReadingProgressController.ReadingProgressResponse> response =
                subject.getReadingProgress(book.getId(), authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("epubcfi(/6/8)", response.getBody().location());
        assertEquals(0.25, response.getBody().progress());
    }

    @Test
    void getReadingProgressReturnsNotFoundForAnUnknownBook() {
        UUID unknown = UUID.randomUUID();
        when(bookRepository.findById(unknown)).thenReturn(Optional.empty());

        ResponseEntity<ReadingProgressController.ReadingProgressResponse> response =
                subject.getReadingProgress(unknown, authentication);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    /** The reader resumes from both coordinate systems, so it gets the epubs and every chapter. */
    @Test
    void getBookProgressReturnsReadingPositionChaptersAndEpubs() {
        UserEntity user = UserEntity.builder().name("u").externalId("sub-1").build();
        user.setId(UUID.randomUUID());
        ChapterEntity first = chapters.get(0);
        first.getMediaFileEntities().add(MediaFileEntity.builder().durationInMilliseconds(60_000L).build());

        WatchStatusEntity chapterStatus = chapterStatuses.get(first.getId());
        chapterStatus.setWatched(true);
        chapterStatus.setProgressInMilliseconds(30_000L);
        chapterStatus.setDateUpdated(Instant.now());

        WatchStatusEntity reading = WatchStatusEntity.builder().bookEntity(book).build();
        UUID epubId = UUID.randomUUID();
        reading.setReadingLocation("epubcfi(/6/8)");
        reading.setReadingLocationMediaFileId(epubId);
        reading.setReadingProgress(0.3);
        reading.setDateUpdated(Instant.now());

        MediaFileEntity epub = MediaFileEntity.builder().mediaOverlays(true).build();
        epub.setId(epubId);

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findByUserEntityAndChapterEntityBookEntity(user, book))
                .thenReturn(List.of(chapterStatus));
        when(watchStatusService.getOrCreateForBook(authentication, book)).thenReturn(reading);
        when(mediaFileRepository.findByBookEntityId(book.getId())).thenReturn(List.of(epub));

        ResponseEntity<ReadingProgressController.BookProgressResponse> response =
                subject.getBookProgress(book.getId(), authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ReadingProgressController.BookProgressResponse body = response.getBody();
        assertEquals("epubcfi(/6/8)", body.reading().location());
        assertEquals(epubId, body.reading().mediaFileId());
        assertEquals(3, body.chapters().size());
        assertEquals(60_000L, body.chapters().getFirst().durationInMilliseconds());
        assertEquals(30_000L, body.chapters().getFirst().progressInMilliseconds());
        assertTrue(body.chapters().getFirst().watched());
        // A chapter the user never started has no status of its own.
        assertEquals(0L, body.chapters().get(1).progressInMilliseconds());
        assertNull(body.chapters().get(1).durationInMilliseconds());
        assertNull(body.chapters().get(1).updatedAt());
        assertEquals(List.of(new ReadingProgressController.EpubFile(epubId, true)), body.epubFiles());
    }

    /** Nothing read yet: no location, and therefore no timestamp to resume from. */
    @Test
    void getBookProgressWithoutAReadingPositionHasNoTimestamp() {
        UserEntity user = UserEntity.builder().name("u").externalId("sub-1").build();
        user.setId(UUID.randomUUID());
        WatchStatusEntity reading = WatchStatusEntity.builder().bookEntity(book).build();
        reading.setDateUpdated(Instant.now());

        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findByUserEntityAndChapterEntityBookEntity(user, book)).thenReturn(List.of());
        when(watchStatusService.getOrCreateForBook(authentication, book)).thenReturn(reading);
        when(mediaFileRepository.findByBookEntityId(book.getId())).thenReturn(List.of());

        ReadingProgressController.BookProgressResponse body =
                subject.getBookProgress(book.getId(), authentication).getBody();

        assertNull(body.reading().location());
        assertNull(body.reading().updatedAt());
        assertTrue(body.epubFiles().isEmpty());
    }

    @Test
    void getBookProgressReturnsNotFoundForAnUnknownBook() {
        UUID unknown = UUID.randomUUID();
        when(bookRepository.findById(unknown)).thenReturn(Optional.empty());

        ResponseEntity<ReadingProgressController.BookProgressResponse> response =
                subject.getBookProgress(unknown, authentication);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }
}
