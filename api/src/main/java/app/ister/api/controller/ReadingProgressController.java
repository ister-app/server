package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.WatchStatusRepository;
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

import java.util.Optional;
import java.util.UUID;

/**
 * REST twin of the updateReadingProgress GraphQL mutation, used by the reader web app: it runs in
 * a plain webview without a GraphQL client and authenticates with the {@code ?token=} stream token
 * (see StreamTokenAuthenticationFilter). The GET returns the stored position so the reader can
 * resume without being handed a location by the native client.
 */
@RestController
@RequiredArgsConstructor
public class ReadingProgressController {
    private final BookRepository bookRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final WatchStatusService watchStatusService;

    public record ReadingProgressRequest(UUID bookId, String location, double progress) {
    }

    public record ReadingProgressResponse(UUID bookId, String location, Double progress, boolean finished) {
    }

    @PreAuthorize("hasRole('user')")
    @PostMapping("/reading-progress")
    public ReadingProgressResponse updateReadingProgress(@RequestBody ReadingProgressRequest request,
                                                         Authentication authentication) {
        BookEntity book = bookRepository.findById(request.bookId()).orElseThrow();
        WatchStatusEntity watchStatus = watchStatusService.getOrCreateForBook(authentication, book);
        watchStatus.setReadingLocation(request.location());
        watchStatus.setReadingProgress(Math.clamp(request.progress(), 0.0, 1.0));
        watchStatus.setWatched(request.progress() >= 0.97);
        watchStatusRepository.save(watchStatus);
        return toResponse(book, watchStatus);
    }

    @PreAuthorize("hasRole('user')")
    @GetMapping("/reading-progress")
    public ResponseEntity<ReadingProgressResponse> getReadingProgress(@RequestParam UUID bookId,
                                                                      Authentication authentication) {
        Optional<BookEntity> book = bookRepository.findById(bookId);
        if (book.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        WatchStatusEntity watchStatus = watchStatusService.getOrCreateForBook(authentication, book.get());
        return ResponseEntity.ok(toResponse(book.get(), watchStatus));
    }

    private static ReadingProgressResponse toResponse(BookEntity book, WatchStatusEntity watchStatus) {
        return new ReadingProgressResponse(book.getId(), watchStatus.getReadingLocation(),
                watchStatus.getReadingProgress(), watchStatus.isWatched());
    }
}
