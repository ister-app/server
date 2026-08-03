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
     *
     * <p>When this <em>creates</em> the series, the author's other series-less books get a fresh
     * BOOK_FOUND: their Wikidata series discovery may have run before the series existed (scan
     * order is arbitrary) and only links into existing series. Once per new series, so a large
     * series does not re-fire on every volume; BOOK_FOUND never creates series, so no loop.
     */
    public void assignFromEpub(BookEntity book, String seriesName, Double seriesIndex) {
        if (seriesName == null || seriesName.isBlank()) {
            return;
        }
        if (book.getPersonEntity() == null) {
            // A comic volume: its series comes from the series directory, not from epub metadata,
            // and book series are keyed per author.
            return;
        }
        String name = seriesName.strip();
        boolean isNewSeries = seriesRepository
                .findByPersonEntityAndName(book.getPersonEntity(), name).isEmpty();
        SeriesEntity series = getOrCreateSeries(book.getLibraryEntity(), book.getPersonEntity(), name);
        book.setSeriesEntity(series);
        book.setSeriesIndex(seriesIndex);
        updateDisplayTitle(book);
        if (isNewSeries) {
            refireSerieslessBooks(book);
        }
    }

    /** BOOK_FOUND for the author's books still without a series, the linked book excluded. */
    private void refireSerieslessBooks(BookEntity book) {
        bookRepository.findByPersonEntityId(book.getPersonEntity().getId()).stream()
                .filter(other -> other.getSeriesEntity() == null && !other.getId().equals(book.getId()))
                .forEach(other -> serverEventService.createBookFoundEvent(other.getId()));
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
        int[] separator = findSeparator(remainder);
        if (separator.length == 0 || separator[0] != 0) {
            return null;
        }
        String title = remainder.substring(separator[1]).strip();
        return title.isEmpty() ? null : title;
    }

    /** The part of the name before the first separator, or null when there is none. */
    private String prefixOf(String name) {
        int[] separator = findSeparator(name);
        if (separator.length == 0 || separator[0] == 0) {
            return null;
        }
        return name.substring(0, separator[0]).strip();
    }

    private static final int[] NO_SEPARATOR = new int[0];

    /**
     * First series/title separator: a spaced dash (" - ", " – ", " — ") or a colon (whitespace
     * allowed before, required after). Hand-rolled rather than a regex: the "start of a whitespace
     * run" restriction needs a lookbehind, which Sonar's backtracking analysis cannot see through.
     *
     * @return {@code {start, end}} of the separator including its surrounding whitespace, or an
     *         empty array when there is none
     */
    private static int[] findSeparator(String value) {
        int length = value.length();
        for (int i = 0; i < length; i++) {
            if (i > 0 && Character.isWhitespace(value.charAt(i - 1))) {
                continue; // a separator match starts at the beginning of a whitespace run
            }
            int mark = skipWhitespace(value, i);
            if (mark == length) {
                return NO_SEPARATOR; // trailing whitespace, nothing can follow
            }
            int end = separatorEnd(value, i, mark);
            if (end >= 0) {
                return new int[]{i, end};
            }
        }
        return NO_SEPARATOR;
    }

    /** Index of the first non-whitespace character at or after {@code from}. */
    private static int skipWhitespace(String value, int from) {
        int mark = from;
        while (mark < value.length() && Character.isWhitespace(value.charAt(mark))) {
            mark++;
        }
        return mark;
    }

    /**
     * End of a separator whose whitespace run starts at {@code start} and whose separator
     * character sits at {@code mark} (a dash needs whitespace before it, a colon does not; both
     * need whitespace after), or -1 when there is no separator here.
     */
    private static int separatorEnd(String value, int start, int mark) {
        char c = value.charAt(mark);
        boolean spacedDash = mark > start && (c == '-' || c == '–' || c == '—');
        if (!spacedDash && c != ':') {
            return -1;
        }
        int end = skipWhitespace(value, mark + 1);
        return end > mark + 1 ? end : -1;
    }

    private String normalize(String prefix) {
        return prefix.toLowerCase(Locale.ROOT);
    }
}
