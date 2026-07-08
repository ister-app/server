package app.ister.worker.events.tmdbmetadata;

import app.ister.tmdbapi.model.SearchMovie200ResponseResultsInner;
import app.ister.tmdbapi.model.SearchTv200ResponseResultsInner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Picks the best matching result from a TMDB search response instead of blindly taking the first
 * one. TMDB orders by its own relevance score, which routinely floats a spin-off or "collection"
 * above the real series/movie (e.g. searching "Fairly Odd Parents" 2001 returns
 * "The Fairly OddParents Superhero Spectacle" before "The Fairly OddParents").
 *
 * <p>Selection strategy: prefer results whose (localized or original) title matches the query after
 * normalisation (case, punctuation and a leading "the" removed); among the remaining candidates pick
 * the most popular one. Falls back to the single/most-popular result when nothing matches exactly.
 */
@Component
public class TmdbResultSelector {

    public Optional<SearchTv200ResponseResultsInner> selectTv(List<SearchTv200ResponseResultsInner> results, String query) {
        return choose(results, query,
                SearchTv200ResponseResultsInner::getName,
                SearchTv200ResponseResultsInner::getOriginalName,
                SearchTv200ResponseResultsInner::getPopularity);
    }

    public Optional<SearchMovie200ResponseResultsInner> selectMovie(List<SearchMovie200ResponseResultsInner> results, String query) {
        return choose(results, query,
                SearchMovie200ResponseResultsInner::getTitle,
                SearchMovie200ResponseResultsInner::getOriginalTitle,
                SearchMovie200ResponseResultsInner::getPopularity);
    }

    private <T> Optional<T> choose(List<T> results, String query,
                                   Function<T, String> title,
                                   Function<T, String> originalTitle,
                                   Function<T, BigDecimal> popularity) {
        if (results == null || results.isEmpty()) {
            return Optional.empty();
        }
        if (results.size() == 1) {
            return Optional.of(results.getFirst());
        }
        String normalizedQuery = normalize(query);
        Comparator<T> byPopularity = Comparator.comparing(
                r -> Optional.ofNullable(popularity.apply(r)).orElse(BigDecimal.ZERO));

        List<T> exactMatches = results.stream()
                .filter(r -> matchesQuery(normalizedQuery, title.apply(r), originalTitle.apply(r)))
                .toList();

        List<T> pool = exactMatches.isEmpty() ? results : exactMatches;
        return pool.stream().max(byPopularity);
    }

    private boolean matchesQuery(String normalizedQuery, String title, String originalTitle) {
        if (normalizedQuery.isEmpty()) {
            return false;
        }
        return normalizedQuery.equals(normalize(title)) || normalizedQuery.equals(normalize(originalTitle));
    }

    /**
     * Lowercases, drops a leading "the", and removes everything that is not a letter or digit so that
     * "The Fairly OddParents" and "Fairly Odd Parents" both collapse to "fairlyoddparents".
     */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lowered = value.toLowerCase(java.util.Locale.ROOT).strip();
        if (lowered.startsWith("the ")) {
            lowered = lowered.substring(4);
        }
        return lowered.replaceAll("[^a-z0-9]", "");
    }
}
