package app.ister.worker.events.tmdbmetadata;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

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
}
