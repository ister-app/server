package app.ister.worker.events.tmdbmetadata;

import app.ister.tmdbapi.model.SearchMovie200ResponseResultsInner;
import app.ister.tmdbapi.model.SearchTv200ResponseResultsInner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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
    /** How far a directory's year may be off from TMDB's release year and still count as the same title. */
    static final int YEAR_TOLERANCE = 2;


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

    /** Exact (normalized) title matches only, most popular first; empty when nothing matches exactly. */
    public Optional<SearchTv200ResponseResultsInner> selectTvExact(List<SearchTv200ResponseResultsInner> results, String query) {
        return mostPopular(exactMatches(results, query, SearchTv200ResponseResultsInner::getName,
                SearchTv200ResponseResultsInner::getOriginalName), SearchTv200ResponseResultsInner::getPopularity);
    }

    public Optional<SearchMovie200ResponseResultsInner> selectMovieExact(List<SearchMovie200ResponseResultsInner> results, String query) {
        return mostPopular(exactMatches(results, query, SearchMovie200ResponseResultsInner::getTitle,
                SearchMovie200ResponseResultsInner::getOriginalTitle), SearchMovie200ResponseResultsInner::getPopularity);
    }

    /**
     * Exact title matches whose first air date lies within {@link #YEAR_TOLERANCE} of the given
     * year: the pick for a directory whose year is slightly off ("V for Vendetta (2005)" for the
     * 2006 film). Empty when no exact match sits near the year.
     */
    public Optional<SearchTv200ResponseResultsInner> selectTvNearYear(List<SearchTv200ResponseResultsInner> results, String query, int year) {
        return mostPopular(exactMatches(results, query, SearchTv200ResponseResultsInner::getName,
                        SearchTv200ResponseResultsInner::getOriginalName).stream()
                        .filter(r -> nearYear(r.getFirstAirDate(), year)).toList(),
                SearchTv200ResponseResultsInner::getPopularity);
    }

    public Optional<SearchMovie200ResponseResultsInner> selectMovieNearYear(List<SearchMovie200ResponseResultsInner> results, String query, int year) {
        return mostPopular(exactMatches(results, query, SearchMovie200ResponseResultsInner::getTitle,
                        SearchMovie200ResponseResultsInner::getOriginalTitle).stream()
                        .filter(r -> nearYear(r.getReleaseDate(), year)).toList(),
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
        List<T> exact = exactMatches(results, query, title, originalTitle);
        if (!exact.isEmpty()) {
            return mostPopular(exact, popularity);
        }
        // No exact title: TMDB's relevance order is only trusted for results that share at least
        // half of the query's words ("Birds of Prey (and the ...)", "DuckTales: The Movie -
        // Treasure of the Lost Lamp"). Anything else is a wrong year's random hit ("300" (2006)
        // → "Rob-B-Hood") and is better left without metadata than mislabelled.
        Set<String> queryTokens = tokens(query);
        List<T> similar = results.stream()
                .filter(r -> similar(queryTokens, title.apply(r)) || similar(queryTokens, originalTitle.apply(r)))
                .toList();
        return mostPopular(similar, popularity);
    }

    private <T> List<T> exactMatches(List<T> results, String query, Function<T, String> title, Function<T, String> originalTitle) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        String normalizedQuery = normalize(query);
        return results.stream()
                .filter(r -> matchesQuery(normalizedQuery, title.apply(r), originalTitle.apply(r)))
                .toList();
    }

    private <T> Optional<T> mostPopular(List<T> pool, Function<T, BigDecimal> popularity) {
        return pool.stream().max(Comparator.comparing(
                r -> Optional.ofNullable(popularity.apply(r)).orElse(BigDecimal.ZERO)));
    }

    static boolean nearYear(String date, int year) {
        if (date == null || date.length() < 4) {
            return false;
        }
        try {
            return Math.abs(Integer.parseInt(date.substring(0, 4)) - year) <= YEAR_TOLERANCE;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * At least half of the query's words occur in the candidate title, and the two share at least
     * two words unless the candidate is that short. One shared word is too little: "300" also
     * occurs in "Home Movies 300-1".
     */
    static boolean similar(Set<String> queryTokens, String candidate) {
        if (queryTokens.isEmpty()) {
            return false;
        }
        Set<String> candidateTokens = tokens(candidate);
        long shared = queryTokens.stream().filter(candidateTokens::contains).count();
        return shared * 2 >= queryTokens.size() && (shared >= 2 || candidateTokens.size() <= 2);
    }

    private static final Set<String> STOP_WORDS = Set.of("the", "a", "an", "of", "and", "in", "on", "at", "to",
            "de", "het", "een", "en", "van");

    static Set<String> tokens(String value) {
        if (value == null) {
            return Set.of();
        }
        return Arrays.stream(value.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> !t.isEmpty() && !STOP_WORDS.contains(t))
                .collect(Collectors.toSet());
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
