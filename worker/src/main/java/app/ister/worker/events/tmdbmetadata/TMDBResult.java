package app.ister.worker.events.tmdbmetadata;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Builder
@Getter
@Setter
public class TMDBResult {
    String language;
    String title;
    LocalDate released;
    String sourceUri;
    String description;
    String posterUrl;
    String backgroundUrl;
    // TMDB id of the movie/show/episode itself.
    Integer tmdbId;
    // Only set for episodes: the TMDB id of the series, needed for episode credits.
    Integer seriesTmdbId;

    // Localized per language request, stored on the metadata row.
    String tagline;
    /** Comma-separated localized genre names. */
    String genres;

    // Language independent (identical in every language response); applied to the
    // movie/show/episode entity once, on the first successful language.
    Integer runtime;
    BigDecimal voteAverage;
    Integer voteCount;
    String status;
    String homepage;
    String imdbId;
    Integer collectionTmdbId;
    String collectionName;
    /** Comma-separated production company names. */
    String studios;
    /** Comma-separated network names (TV only). */
    String networks;
    /** Comma-separated ISO 3166-1 country codes. */
    String originCountry;
}
