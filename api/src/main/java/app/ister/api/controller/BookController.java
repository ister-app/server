package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.BookResumeService;
import app.ister.core.service.ContinueWatchingService;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.UserService;
import app.ister.core.service.WatchStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class BookController {
    private final BookRepository bookRepository;
    private final PersonRepository personRepository;
    private final ImageRepository imageRepository;
    private final MediaFileRepository mediaFileRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final WatchStatusService watchStatusService;
    private final ContinueWatchingService continueWatchingService;
    private final BookResumeService bookResumeService;
    private final UserService userService;
    private final LibraryAccessService libraryAccessService;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<BookEntity> bookById(@Argument UUID id, Authentication authentication) {
        return bookRepository.findById(id)
                .filter(book -> libraryAccessService.canAccess(book.getLibraryEntity(), authentication));
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Page<BookEntity> books(
            @Argument Optional<Integer> page,
            @Argument Optional<Integer> size,
            @Argument Optional<SortingEnum> sorting,
            @Argument Optional<SortingOrder> sortingOrder,
            @Argument Optional<UUID> authorId,
            @Argument Optional<UUID> libraryId, Authentication authentication) {
        Pageable pageable = Paging.pageable(page, size, 20,
                sorting, SortingEnum.NAME, sortingOrder, SortingOrder.ASCENDING);
        if (authorId.isPresent()) {
            return personRepository.findById(authorId.get())
                    .map(author -> libraryAccessService.allowedLibraryIds(authentication)
                            .map(allowed -> bookRepository.findByPersonEntityAndLibraryEntityIdIn(author, allowed, pageable))
                            .orElseGet(() -> bookRepository.findByPersonEntity(author, pageable)))
                    .orElseGet(() -> Page.empty(pageable));
        }
        if (libraryId.isPresent()) {
            return libraryId.filter(id -> libraryAccessService.canAccess(id, authentication))
                    .map(id -> bookRepository.findByLibraryEntityId(id, pageable))
                    .orElseGet(() -> Page.empty(pageable));
        }
        return libraryAccessService.allowedLibraryIds(authentication)
                .map(allowed -> bookRepository.findByLibraryEntityIdIn(allowed, pageable))
                .orElseGet(() -> bookRepository.findAll(pageable));
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public WatchStatusEntity updateReadingProgress(@Argument UUID bookId, @Argument String location,
                                                   @Argument double progress, Authentication authentication) {
        BookEntity book = bookRepository.findById(bookId)
                .filter(found -> libraryAccessService.canAccess(found.getLibraryEntity(), authentication))
                .orElseThrow(() -> new NoSuchElementException("Book not found: " + bookId));
        WatchStatusEntity watchStatus = watchStatusService.getOrCreateForBook(authentication, book);
        watchStatus.setReadingLocation(location);
        watchStatus.setReadingProgress(Math.clamp(progress, 0.0, 1.0));
        watchStatus.setWatched(progress >= 0.97);
        watchStatusRepository.save(watchStatus);
        continueWatchingService.onWatchStatusChanged(watchStatus);
        return watchStatus;
    }

    @SchemaMapping(typeName = "Book", field = "author")
    public PersonEntity author(BookEntity bookEntity) {
        return bookEntity.getPersonEntity();
    }

    /** Clean display title: name with a known series prefix stripped; falls back to the raw name. */
    @SchemaMapping(typeName = "Book", field = "title")
    public String title(BookEntity bookEntity) {
        return bookEntity.getTitle() != null ? bookEntity.getTitle() : bookEntity.getName();
    }

    @SchemaMapping(typeName = "Book", field = "series")
    public SeriesEntity series(BookEntity bookEntity) {
        return bookEntity.getSeriesEntity();
    }

    @SchemaMapping(typeName = "Book", field = "seriesIndex")
    public Double seriesIndex(BookEntity bookEntity) {
        return bookEntity.getSeriesIndex();
    }

    /**
     * The book's own release year is only set when the file/directory name carries a "(YYYY)"
     * suffix; otherwise the year lives in the metadata parsed from the epub, nfo or Open Library.
     */
    @SchemaMapping(typeName = "Book", field = "releaseYear")
    public int releaseYear(BookEntity bookEntity) {
        if (bookEntity.getReleaseYear() > 0) {
            return bookEntity.getReleaseYear();
        }
        return bookEntity.getMetadataEntities().stream()
                .map(MetadataEntity::getReleased)
                .filter(Objects::nonNull)
                .mapToInt(LocalDate::getYear)
                .min()
                .orElse(0);
    }

    @SchemaMapping(typeName = "Book", field = "chapters")
    public List<ChapterEntity> chapters(BookEntity bookEntity) {
        return bookEntity.getChapterEntities();
    }

    @SchemaMapping(typeName = "Book", field = "resumeChapter")
    public ChapterEntity resumeChapter(BookEntity bookEntity, Authentication authentication) {
        return bookResumeService.resume(userService.getOrCreateUser(authentication), bookEntity.getId())
                .map(BookResumeService.ChapterResume::chapter)
                .orElse(null);
    }

    @SchemaMapping(typeName = "Book", field = "epubFiles")
    public List<MediaFileEntity> epubFiles(BookEntity bookEntity) {
        return mediaFileRepository.findByBookEntityId(bookEntity.getId());
    }

    /**
     * Deterministic source preference: NFO, then epub, then Open Library. Clients that take the
     * first row therefore show stable titles/descriptions instead of arbitrary JPA row order.
     */
    @SchemaMapping(typeName = "Book", field = "metadata")
    public List<MetadataEntity> metadata(BookEntity bookEntity) {
        return bookEntity.getMetadataEntities().stream()
                .sorted(Comparator.comparingInt(BookController::sourceRank)
                        .thenComparing(MetadataEntity::getDateCreated,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static int sourceRank(MetadataEntity metadata) {
        String sourceUri = metadata.getSourceUri() == null ? "" : metadata.getSourceUri();
        if (sourceUri.endsWith(".nfo")) {
            return 0;
        }
        if (sourceUri.endsWith(".epub")) {
            return 1;
        }
        if (sourceUri.startsWith("openlibrary://")) {
            return 2;
        }
        return 3;
    }

    @BatchMapping(typeName = "Book", field = "images")
    public Map<BookEntity, List<ImageEntity>> images(List<BookEntity> books) {
        List<UUID> ids = books.stream().map(BookEntity::getId).toList();
        Map<UUID, List<ImageEntity>> byBookId = imageRepository.findByBookEntityIdIn(ids).stream()
                .collect(Collectors.groupingBy(ImageEntity::getBookEntityId));
        return books.stream().collect(Collectors.toMap(b -> b, b -> byBookId.getOrDefault(b.getId(), List.of())));
    }

    @BatchMapping(typeName = "Book", field = "watchStatus")
    public Map<BookEntity, List<WatchStatusEntity>> watchStatus(List<BookEntity> books, Authentication authentication) {
        Map<UUID, List<WatchStatusEntity>> byBookId = watchStatusRepository
                .findByUserEntityExternalIdAndBookEntityIn(authentication.getName(), books, Sort.by("dateUpdated").descending()).stream()
                .collect(Collectors.groupingBy(w -> w.getBookEntity().getId()));
        return books.stream().collect(Collectors.toMap(b -> b, b -> byBookId.getOrDefault(b.getId(), List.of())));
    }
}
