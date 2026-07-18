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
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.UserService;
import app.ister.core.service.WatchStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * REST twin of the updateReadingProgress GraphQL mutation, used by the reader web app: it runs in
 * a plain webview without a GraphQL client and authenticates with the {@code ?token=} stream token
 * (see StreamTokenAuthenticationFilter). The GET returns the stored position so the reader can
 * resume without being handed a location by the native client.
 *
 * <p>A book can be consumed as text and as audio, and both have to resume in the same place. The
 * translation between an epubcfi and a chapter position lives in the reader web app: it is the only
 * component that knows the epub's structure (spine, TOC, and the SMIL of a read-aloud edition). This
 * controller serves the reader both positions ({@code GET /book-progress}) and stores both when it
 * saves ({@code POST /reading-progress} with a chapter position attached).
 */
@RestController
@RequiredArgsConstructor
public class ReadingProgressController {
    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final MediaFileRepository mediaFileRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final WatchStatusService watchStatusService;
    private final ContinueWatchingService continueWatchingService;
    private final UserService userService;
    private final LibraryAccessService libraryAccessService;

    /**
     * @param chapterId                  the audiobook chapter the reading position maps onto, or
     *                                   null when the reader could not map it (book without audio)
     * @param positionInMilliseconds     the derived position within that chapter
     * @param readingLocationMediaFileId the epub {@code location} was recorded in
     */
    public record ReadingProgressRequest(UUID bookId, String location, double progress,
                                         UUID chapterId, Long positionInMilliseconds,
                                         UUID readingLocationMediaFileId) {
    }

    public record ReadingProgressResponse(UUID bookId, String location, Double progress, boolean finished) {
    }

    public record ChapterProgress(UUID id, int number, Long durationInMilliseconds,
                                  long progressInMilliseconds, boolean watched, Instant updatedAt) {
    }

    public record ReadingPosition(String location, UUID mediaFileId, Double progress, Instant updatedAt) {
    }

    public record EpubFile(UUID id, boolean mediaOverlays) {
    }

    /** Everything the reader needs to resume, in both coordinate systems. */
    public record BookProgressResponse(UUID bookId, ReadingPosition reading,
                                       List<ChapterProgress> chapters, List<EpubFile> epubFiles) {
    }

    @PreAuthorize("hasRole('user')")
    @PostMapping("/reading-progress")
    public ReadingProgressResponse updateReadingProgress(@RequestBody ReadingProgressRequest request,
                                                         Authentication authentication) {
        BookEntity book = bookRepository.findById(request.bookId())
                .filter(found -> libraryAccessService.canAccess(found.getLibraryEntity(), authentication))
                .orElseThrow();
        WatchStatusEntity watchStatus = watchStatusService.getOrCreateForBook(authentication, book);
        watchStatus.setReadingLocation(request.location());
        watchStatus.setReadingLocationMediaFileId(request.readingLocationMediaFileId());
        watchStatus.setReadingProgress(Math.clamp(request.progress(), 0.0, 1.0));
        watchStatus.setWatched(request.progress() >= 0.97);
        watchStatusRepository.save(watchStatus);
        continueWatchingService.onWatchStatusChanged(watchStatus);

        if (request.chapterId() != null && request.positionInMilliseconds() != null) {
            applyListeningPosition(authentication, book, request.chapterId(), request.positionInMilliseconds());
        }
        return toResponse(book, watchStatus);
    }

    @PreAuthorize("hasRole('user')")
    @GetMapping("/reading-progress")
    public ResponseEntity<ReadingProgressResponse> getReadingProgress(@RequestParam UUID bookId,
                                                                      Authentication authentication) {
        Optional<BookEntity> book = bookRepository.findById(bookId)
                .filter(found -> libraryAccessService.canAccess(found.getLibraryEntity(), authentication));
        if (book.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        WatchStatusEntity watchStatus = watchStatusService.getOrCreateForBook(authentication, book.get());
        return ResponseEntity.ok(toResponse(book.get(), watchStatus));
    }

    @PreAuthorize("hasRole('user')")
    @GetMapping("/book-progress")
    public ResponseEntity<BookProgressResponse> getBookProgress(@RequestParam UUID bookId,
                                                                Authentication authentication) {
        Optional<BookEntity> found = bookRepository.findById(bookId)
                .filter(book -> libraryAccessService.canAccess(book.getLibraryEntity(), authentication));
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        BookEntity book = found.get();
        UserEntity user = userService.getOrCreateUser(authentication);

        Map<UUID, WatchStatusEntity> byChapterId = watchStatusRepository
                .findByUserEntityAndChapterEntityBookEntity(user, book).stream()
                .collect(Collectors.toMap(status -> status.getChapterEntity().getId(), Function.identity(),
                        (first, second) -> first));

        List<ChapterProgress> chapters = book.getChapterEntities().stream()
                .sorted(Comparator.comparingInt(ChapterEntity::getNumber))
                .map(chapter -> {
                    WatchStatusEntity status = byChapterId.get(chapter.getId());
                    return new ChapterProgress(
                            chapter.getId(),
                            chapter.getNumber(),
                            chapter.getMediaFileEntities().stream().findFirst()
                                    .map(MediaFileEntity::getDurationInMilliseconds)
                                    .orElse(null),
                            status != null ? status.getProgressInMilliseconds() : 0,
                            status != null && status.isWatched(),
                            status != null ? status.getDateUpdated() : null);
                })
                .toList();

        WatchStatusEntity reading = watchStatusService.getOrCreateForBook(authentication, book);
        List<EpubFile> epubFiles = mediaFileRepository.findByBookEntityId(book.getId()).stream()
                .map(file -> new EpubFile(file.getId(), Boolean.TRUE.equals(file.getMediaOverlays())))
                .toList();

        return ResponseEntity.ok(new BookProgressResponse(
                book.getId(),
                new ReadingPosition(reading.getReadingLocation(), reading.getReadingLocationMediaFileId(),
                        reading.getReadingProgress(),
                        reading.getReadingLocation() != null ? reading.getDateUpdated() : null),
                chapters,
                epubFiles));
    }

    /**
     * Mirrors a reading position onto the audiobook: the chapter the reader is in gets the derived
     * position, chapters before it count as listened and chapters after it as untouched. The player
     * resumes at the first chapter that is not watched, so without this bookkeeping a switch from
     * reading to listening would drop back to chapter one.
     *
     * <p>The current chapter is written and flushed <em>last</em>, so its {@code dateUpdated} is the
     * newest of the batch. {@link app.ister.core.service.BookResumeService} resumes at the most
     * recently updated chapter; if the whole batch shared a timestamp it could resume at a later,
     * reset chapter instead of the one the reader was actually in.
     */
    private void applyListeningPosition(Authentication authentication, BookEntity book,
                                        UUID chapterId, long positionInMilliseconds) {
        ChapterEntity current = chapterRepository.findById(chapterId).orElse(null);
        if (current == null || !current.getBookEntity().getId().equals(book.getId())) {
            return;
        }
        WatchStatusEntity currentStatus = null;
        for (ChapterEntity chapter : book.getChapterEntities()) {
            WatchStatusEntity status = watchStatusService.getOrCreateForChapter(authentication, chapter);
            int compared = Integer.compare(chapter.getNumber(), current.getNumber());
            if (compared == 0) {
                // Leave the current chapter unchanged for now; it is written after the flush below so
                // it ends up with the newest timestamp.
                currentStatus = status;
            } else if (compared < 0) {
                status.setWatched(true);
                watchStatusRepository.save(status);
            } else {
                status.setWatched(false);
                status.setProgressInMilliseconds(0);
                watchStatusRepository.save(status);
            }
        }
        watchStatusRepository.flush();

        if (currentStatus != null) {
            currentStatus.setWatched(false);
            currentStatus.setProgressInMilliseconds(Math.max(0, positionInMilliseconds));
            watchStatusRepository.saveAndFlush(currentStatus);
            // One update for the whole batch: the continue-watching entry is keyed by book, and the
            // chapter the reader is in is the one it should resume with.
            continueWatchingService.onWatchStatusChanged(currentStatus);
        }
    }

    private static ReadingProgressResponse toResponse(BookEntity book, WatchStatusEntity watchStatus) {
        return new ReadingProgressResponse(book.getId(), watchStatus.getReadingLocation(),
                watchStatus.getReadingProgress(), watchStatus.isWatched());
    }
}
