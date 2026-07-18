package app.ister.worker.events.wikipedia;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static app.ister.worker.events.musicbrainz.MusicBrainzService.normalizeTitle;

/**
 * Series position and original publication year for a book, from Wikidata. The book item is found
 * by title, must be part of a series (P179) whose label matches the book's series name — a title
 * search also returns the film and the game — and then the "series ordinal" qualifier (P1545) and
 * the earliest publication date (P577, the original edition, not the local reprint) are read.
 * Wikidata's coverage of translated titles is thin, so a miss is normal and non-fatal; local epub
 * series metadata keeps precedence over anything found here.
 *
 * <p>{@link #discoverSeries} works the other way around: for a book whose series is unknown it
 * reads which of the author's <em>existing</em> series the Wikidata item belongs to. That covers
 * titles the path-prefix heuristic can never split ("Harry Potter en de steen der wijzen" has no
 * separator) and audiobook-only books without epub series metadata. Discovery additionally
 * requires an author (P50) label match, so the film or the game — same title, different creator
 * statement — can never link a book into a series.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WikidataBookSeriesService {

    private static final String AUTHOR = "P50";
    private static final String PART_OF_SERIES = "P179";
    private static final String SERIES_ORDINAL = "P1545";
    private static final String PUBLICATION_DATE = "P577";

    private final WikipediaService wikipediaService;

    /** Either field may be null: membership can be confirmed without an ordinal or a date. */
    public record BookSeriesInfo(String wikidataId, Double seriesIndex, Integer firstPublicationYear) {}

    /** {@code seriesName} is the caller's candidate name that matched, verbatim — the caller uses
     * it to look the {@code SeriesEntity} back up. Index and year may be null, as above. */
    public record DiscoveredSeries(String wikidataId, String seriesName, Double seriesIndex,
                                   Integer firstPublicationYear) {}

    public Optional<BookSeriesInfo> findBookInSeries(String bookTitle, String seriesName, List<String> languageTags) {
        if (bookTitle == null || bookTitle.isBlank() || seriesName == null || seriesName.isBlank()
                || languageTags == null || languageTags.isEmpty()) {
            return Optional.empty();
        }
        String wantedTitle = normalizeTitle(bookTitle);
        for (String tag : languageTags) {
            for (String candidateId : wikipediaService.searchEntityIds(bookTitle, tag)) {
                Map<String, Object> entity = wikipediaService.fetchWikidataEntity(candidateId);
                if (!wikipediaService.labelMatches(entity, candidateId, wantedTitle, languageTags)) {
                    continue;
                }
                Optional<BookSeriesInfo> info = fromEntity(entity, candidateId, seriesName, languageTags);
                if (info.isPresent()) {
                    return info;
                }
            }
        }
        log.debug("No Wikidata series match for book={} series={}", bookTitle, seriesName);
        return Optional.empty();
    }

    /**
     * Which of the author's known series a book belongs to, per its Wikidata item. A candidate is
     * accepted only when its title label matches, one of its P179 series labels matches one of
     * {@code candidateSeriesNames}, <em>and</em> one of its P50 (author) labels matches
     * {@code authorName} — an item without an author statement is rejected, never guessed at.
     */
    public Optional<DiscoveredSeries> discoverSeries(String bookTitle, String authorName,
                                                     Collection<String> candidateSeriesNames,
                                                     List<String> languageTags) {
        if (bookTitle == null || bookTitle.isBlank() || authorName == null || authorName.isBlank()
                || candidateSeriesNames == null || candidateSeriesNames.isEmpty()
                || languageTags == null || languageTags.isEmpty()) {
            return Optional.empty();
        }
        String wantedTitle = normalizeTitle(bookTitle);
        for (String tag : languageTags) {
            for (String candidateId : wikipediaService.searchEntityIds(bookTitle, tag)) {
                Map<String, Object> entity = wikipediaService.fetchWikidataEntity(candidateId);
                if (!wikipediaService.labelMatches(entity, candidateId, wantedTitle, languageTags)
                        || !(wikipediaService.entityField(entity, candidateId, "claims")
                                instanceof Map<?, ?> claims)) {
                    continue;
                }
                // Series first: most false candidates (the film, the game) fail here without the
                // extra author-entity fetch.
                Optional<DiscoveredSeries> match =
                        seriesFromClaims(claims, candidateId, candidateSeriesNames, languageTags);
                if (match.isPresent() && authorMatches(claims, authorName, languageTags)) {
                    return match;
                }
            }
        }
        log.debug("No Wikidata series discovered for book={} author={}", bookTitle, authorName);
        return Optional.empty();
    }

    /** The candidate is accepted only when one of its P179 statements points at our series. */
    private Optional<BookSeriesInfo> fromEntity(Map<String, Object> entity, String entityId,
                                                String seriesName, List<String> languageTags) {
        if (!(wikipediaService.entityField(entity, entityId, "claims") instanceof Map<?, ?> claims)) {
            return Optional.empty();
        }
        return seriesFromClaims(claims, entityId, List.of(seriesName), languageTags)
                .map(found -> new BookSeriesInfo(
                        found.wikidataId(), found.seriesIndex(), found.firstPublicationYear()));
    }

    /** The first P179 statement whose series label matches one of the candidate names wins. */
    private Optional<DiscoveredSeries> seriesFromClaims(Map<?, ?> claims, String entityId,
                                                        Collection<String> candidateSeriesNames,
                                                        List<String> languageTags) {
        if (!(claims.get(PART_OF_SERIES) instanceof List<?> seriesClaims)) {
            return Optional.empty();
        }
        for (Object claim : seriesClaims) {
            if (!(claim instanceof Map<?, ?> statement)) {
                continue;
            }
            String seriesId = mainsnakItemId(statement);
            if (seriesId == null) {
                continue;
            }
            Map<String, Object> seriesEntity = wikipediaService.fetchWikidataEntity(seriesId);
            for (String candidateName : candidateSeriesNames) {
                if (wikipediaService.labelMatches(
                        seriesEntity, seriesId, normalizeTitle(candidateName), languageTags)) {
                    return Optional.of(new DiscoveredSeries(entityId, candidateName,
                            ordinalOf(statement),
                            earliestPublicationYear(claims)));
                }
            }
        }
        return Optional.empty();
    }

    /** True when one of the item's P50 (author) statements resolves to a label matching the name. */
    private boolean authorMatches(Map<?, ?> claims, String authorName, List<String> languageTags) {
        if (!(claims.get(AUTHOR) instanceof List<?> authorClaims)) {
            return false;
        }
        String wantedAuthor = normalizeTitle(authorName);
        return authorClaims.stream().anyMatch(claim -> {
            if (!(claim instanceof Map<?, ?> statement)) {
                return false;
            }
            String authorId = mainsnakItemId(statement);
            return authorId != null && wikipediaService.labelMatches(
                    wikipediaService.fetchWikidataEntity(authorId), authorId, wantedAuthor, languageTags);
        });
    }

    private String mainsnakItemId(Map<?, ?> statement) {
        return statement.get("mainsnak") instanceof Map<?, ?> snak
                && snak.get("datavalue") instanceof Map<?, ?> value
                && value.get("value") instanceof Map<?, ?> item
                && item.get("id") instanceof String id ? id : null;
    }

    /** The P1545 qualifier of the P179 statement; fractions ("1.5") are legal series positions. */
    private Double ordinalOf(Map<?, ?> statement) {
        if (!(statement.get("qualifiers") instanceof Map<?, ?> qualifiers)
                || !(qualifiers.get(SERIES_ORDINAL) instanceof List<?> ordinals)) {
            return null;
        }
        for (Object ordinal : ordinals) {
            if (ordinal instanceof Map<?, ?> snak
                    && snak.get("datavalue") instanceof Map<?, ?> value
                    && value.get("value") instanceof String text) {
                try {
                    return Double.valueOf(text.strip());
                } catch (NumberFormatException _) {
                    log.debug("Unparseable series ordinal '{}'", text);
                }
            }
        }
        return null;
    }

    /** Earliest P577 year: with several publication dates the first edition is the original one. */
    private Integer earliestPublicationYear(Map<?, ?> claims) {
        if (!(claims.get(PUBLICATION_DATE) instanceof List<?> dates)) {
            return null;
        }
        return dates.stream()
                .map(this::yearOf)
                .filter(year -> year != null && year > 0)
                .min(Integer::compareTo)
                .orElse(null);
    }

    /** Wikidata time values look like "+2004-11-01T00:00:00Z"; only the year part is trusted. */
    private Integer yearOf(Object dateClaim) {
        if (!(dateClaim instanceof Map<?, ?> statement)
                || !(statement.get("mainsnak") instanceof Map<?, ?> snak)
                || !(snak.get("datavalue") instanceof Map<?, ?> value)
                || !(value.get("value") instanceof Map<?, ?> time)
                || !(time.get("time") instanceof String text)) {
            return null;
        }
        try {
            return Integer.valueOf(text.substring(1, text.indexOf('-', 1)));
        } catch (RuntimeException _) {
            return null;
        }
    }
}
