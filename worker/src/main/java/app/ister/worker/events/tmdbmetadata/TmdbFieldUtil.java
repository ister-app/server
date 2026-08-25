package app.ister.worker.events.tmdbmetadata;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Small mapping helpers shared by the TMDB detail mappers. */
final class TmdbFieldUtil {
    private TmdbFieldUtil() {
    }

    /** Joins the non-blank names of a TMDB list ("Action, Drama"); null when nothing remains. */
    static <T> String joinNonBlank(List<T> items, Function<T, String> toName) {
        if (items == null) {
            return null;
        }
        String joined = items.stream()
                .map(toName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** TMDB reports 0 votes as voteCount 0 with voteAverage 0.0; store null instead. */
    static Integer positiveOrNull(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    static <T> T withVotes(Integer voteCount, T voteAverage) {
        return positiveOrNull(voteCount) == null ? null : voteAverage;
    }
}
