package app.ister.worker.events.wikipedia;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WikidataBookSeriesService {

    private static final String PART_OF_SERIES = "P179";
    private static final String SERIES_ORDINAL = "P1545";
    private static final String PUBLICATION_DATE = "P577";

    private final WikipediaService wikipediaService;

    /** Either field may be null: membership can be confirmed without an ordinal or a date. */
    public record BookSeriesInfo(String wikidataId, Double seriesIndex, Integer firstPublicationYear) {}

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

    /** The candidate is accepted only when one of its P179 statements points at our series. */
    private Optional<BookSeriesInfo> fromEntity(Map<String, Object> entity, String entityId,
                                                String seriesName, List<String> languageTags) {
        if (!(wikipediaService.entityField(entity, entityId, "claims") instanceof Map<?, ?> claims)
                || !(claims.get(PART_OF_SERIES) instanceof List<?> seriesClaims)) {
            return Optional.empty();
        }
        String wantedSeries = normalizeTitle(seriesName);
        for (Object claim : seriesClaims) {
            if (!(claim instanceof Map<?, ?> statement)) {
                continue;
            }
            String seriesId = mainsnakItemId(statement);
            if (seriesId == null) {
                continue;
            }
            Map<String, Object> seriesEntity = wikipediaService.fetchWikidataEntity(seriesId);
            if (wikipediaService.labelMatches(seriesEntity, seriesId, wantedSeries, languageTags)) {
                return Optional.of(new BookSeriesInfo(entityId,
                        ordinalOf(statement),
                        earliestPublicationYear(claims)));
            }
        }
        return Optional.empty();
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
