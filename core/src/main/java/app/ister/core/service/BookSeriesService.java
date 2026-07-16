package app.ister.core.service;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Assigns books to a series ("De Grijze Jager") and derives the clean display title.
 *
 * <p>Two sources, with fixed precedence: series metadata from the epub itself (calibre or EPUB 3
 * belongs-to-collection) always wins and (re)writes the link on every scan; the path-prefix
 * heuristic only fills books that have no series yet. The heuristic requires at least two books of
 * the same author sharing the prefix, so a standalone book with " - " in its title is never split,
 * and titles like "Harry Potter en de Steen der Wijzen" (no separator after the series name) are
 * never touched.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BookSeriesService {

    /** " - ", " – ", " — " (spaced dashes) or ": " — the first one splits series from title. */
    private static final Pattern SEPARATOR = Pattern.compile("\\s+[-–—]\\s+|\\s*:\\s+");

    private final SeriesRepository seriesRepository;
    private final BookRepository bookRepository;
    private final ServerEventService serverEventService;

    public SeriesEntity getOrCreateSeries(LibraryEntity libraryEntity, PersonEntity personEntity, String name) {
        return seriesRepository.findByPersonEntityAndName(personEntity, name)
                .orElseGet(() -> seriesRepository.save(SeriesEntity.builder()
                        .libraryEntity(libraryEntity)
                        .personEntity(personEntity)
                        .name(name)
                        .build()));
    }

    /**
     * Series assignment from epub metadata: authoritative, so it always overwrites — a scan or
     * library analyze re-fires EPUB_FILE_FOUND, which lets corrected epub metadata converge over
     * any earlier heuristic assignment.
     */
    public void assignFromEpub(BookEntity book, String seriesName, Double seriesIndex) {
        if (seriesName == null || seriesName.isBlank()) {
            return;
        }
        SeriesEntity series = getOrCreateSeries(book.getLibraryEntity(), book.getPersonEntity(), seriesName.strip());
        book.setSeriesEntity(series);
        book.setSeriesIndex(seriesIndex);
        updateDisplayTitle(book);
    }

    /**
     * Path-prefix fallback: when at least two books of the author share the exact prefix before
     * the first separator, that prefix is the series. Only fills books without a series (epub
     * metadata keeps precedence); the series position stays unknown. Called after each new book of
     * the author (the second book of a series retroactively assigns the first) and from the
     * library analyze as the repair path.
     */
    public void applyPrefixHeuristic(PersonEntity author) {
        List<BookEntity> books = bookRepository.findByPersonEntityId(author.getId());
        Map<String, List<BookEntity>> byPrefix = books.stream()
                .filter(book -> prefixOf(book.getName()) != null)
                .collect(Collectors.groupingBy(book -> normalize(prefixOf(book.getName()))));
        byPrefix.values().stream()
                .filter(group -> group.size() >= 2)
                .forEach(group -> {
                    BookEntity first = group.getFirst();
                    SeriesEntity series = getOrCreateSeries(
                            first.getLibraryEntity(), author, prefixOf(first.getName()));
                    group.stream()
                            .filter(book -> book.getSeriesEntity() == null)
                            .forEach(book -> {
                                book.setSeriesEntity(series);
                                updateDisplayTitle(book);
                            });
                });
    }

    /**
     * Clean display title: the name with a leading "{series} - " / "{series}: " stripped
     * (case-insensitive, dash variants tolerated). Null when the name does not start with the
     * series name — so a title that genuinely contains it without separator stays intact. Never
     * touches {@link BookEntity#getName()}, which is scanner identity. Saves the book and
     * re-indexes it when the title changed.
     */
    public void updateDisplayTitle(BookEntity book) {
        String newTitle = cleanTitle(book);
        if (!Objects.equals(newTitle, book.getTitle())) {
            book.setTitle(newTitle);
            serverEventService.createSearchIndexEvent(SearchEntityType.BOOK, book.getId());
        }
        bookRepository.save(book);
    }

    public void cleanupOrphanSeries() {
        seriesRepository.deleteByBookEntitiesIsEmpty();
    }

    private String cleanTitle(BookEntity book) {
        if (book.getSeriesEntity() == null) {
            return null;
        }
        String name = book.getName();
        String seriesName = book.getSeriesEntity().getName();
        if (name.length() <= seriesName.length()
                || !name.substring(0, seriesName.length()).equalsIgnoreCase(seriesName)) {
            return null;
        }
        String remainder = name.substring(seriesName.length());
        Matcher separator = SEPARATOR.matcher(remainder);
        if (!separator.lookingAt()) {
            return null;
        }
        String title = remainder.substring(separator.end()).strip();
        return title.isEmpty() ? null : title;
    }

    /** The part of the name before the first separator, or null when there is none. */
    private String prefixOf(String name) {
        Matcher separator = SEPARATOR.matcher(name);
        if (!separator.find() || separator.start() == 0) {
            return null;
        }
        return name.substring(0, separator.start()).strip();
    }

    private String normalize(String prefix) {
        return prefix.toLowerCase(Locale.ROOT);
    }
}
