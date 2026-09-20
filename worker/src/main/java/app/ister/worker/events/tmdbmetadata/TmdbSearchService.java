package app.ister.worker.events.tmdbmetadata;

import app.ister.core.config.LanguageProperties;
import app.ister.tmdbapi.model.SearchMovie200Response;
import app.ister.tmdbapi.model.SearchMovie200ResponseResultsInner;
import app.ister.tmdbapi.model.SearchTv200Response;
import app.ister.tmdbapi.model.SearchTv200ResponseResultsInner;
import app.ister.worker.clients.TmdbClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Finds the TMDB movie or series for a scanned {@code Title (year)}. The year in a directory name
 * is often one or two off (US release vs. premiere, DVD year), and TMDB's year filter is strict:
 * a wrong year returns nothing or, worse, unrelated titles that the popularity fallback would then
 * pick. So the lookup widens step by step and stops at the first hit:
 * <ol>
 *   <li>search with the year, exact (normalized) title match;</li>
 *   <li>the same in every other configured language, so a Dutch directory name ("De Smurfen")
 *       matches the Dutch TMDB title;</li>
 *   <li>search without the year, exact title match released within
 *       {@link TmdbResultSelector#YEAR_TOLERANCE} of the directory year;</li>
 *   <li>the year-filtered results without an exact title, accepted only when they share enough
 *       words with the query (see {@link TmdbResultSelector}).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TmdbSearchService {
    private final TmdbClient tmdbClient;
    private final TmdbResultSelector selector;
    private final LanguageProperties languageProperties;

    public Optional<SearchMovie200ResponseResultsInner> findMovie(String name, int year) {
        return find(name, year, "movie",
                (language, withYear) -> movieResults(tmdbClient._searchMovie(name, null, language,
                        withYear ? String.valueOf(year) : null, null, null, null)),
                new Selection<>(selector::selectMovieExact,
                        (results, query) -> selector.selectMovieNearYear(results, query, year),
                        selector::selectMovie,
                        SearchMovie200ResponseResultsInner::getReleaseDate));
    }

    public Optional<SearchTv200ResponseResultsInner> findSeries(String name, int year) {
        return find(name, year, "series",
                (language, withYear) -> seriesResults(tmdbClient._searchTv(name, null, null, language, null,
                        withYear ? year : null)),
                new Selection<>(selector::selectTvExact,
                        (results, query) -> selector.selectTvNearYear(results, query, year),
                        selector::selectTv,
                        SearchTv200ResponseResultsInner::getFirstAirDate));
    }

    /** One TMDB search: in {@code language} (null = TMDB's default), with or without the year filter. */
    @FunctionalInterface
    private interface Search<T> {
        List<T> apply(String language, boolean withYear);
    }

    /** How to pick a result out of a list, from strictest to loosest, plus the result's date for the log. */
    private record Selection<T>(BiFunction<List<T>, String, Optional<T>> exact,
                                BiFunction<List<T>, String, Optional<T>> nearYear,
                                BiFunction<List<T>, String, Optional<T>> fuzzy,
                                Function<T, String> date) {
    }

    private <T> Optional<T> find(String name, int year, String kind, Search<T> search, Selection<T> select) {
        List<T> withYear = search.apply(null, true);
        Optional<T> hit = select.exact().apply(withYear, name);
        if (hit.isPresent()) {
            return hit;
        }
        for (String language : languageProperties.tags()) {
            if (language.equalsIgnoreCase(TmdbLanguageFallback.FALLBACK_LANGUAGE)) {
                continue;
            }
            hit = select.exact().apply(search.apply(language, true), name);
            if (hit.isPresent()) {
                return hit;
            }
        }
        hit = select.nearYear().apply(search.apply(null, false), name);
        if (hit.isPresent()) {
            log.info("Matched {} '{}' ({}) by title to a TMDB release of {}: the directory year is off",
                    kind, name, year, select.date().apply(hit.get()));
            return hit;
        }
        return select.fuzzy().apply(withYear, name);
    }

    private static List<SearchMovie200ResponseResultsInner> movieResults(ResponseEntity<SearchMovie200Response> response) {
        SearchMovie200Response body = response == null ? null : response.getBody();
        return body == null || body.getResults() == null ? List.of() : body.getResults();
    }

    private static List<SearchTv200ResponseResultsInner> seriesResults(ResponseEntity<SearchTv200Response> response) {
        SearchTv200Response body = response == null ? null : response.getBody();
        return body == null || body.getResults() == null ? List.of() : body.getResults();
    }
}
