package app.ister.server.events.TMDBMetadata;

import info.movito.themoviedbapi.TmdbApi;
import info.movito.themoviedbapi.model.core.Movie;
import info.movito.themoviedbapi.model.movies.MovieDb;
import info.movito.themoviedbapi.tools.TmdbException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Get metadata from the movie db.
 * - Overview
 * - Poster url
 * - Background url
 */
@Slf4j
@Component
public class MovieMetadata {
    @Autowired
    private TmdbApi tmdbApi;

    public Optional<TMDBResult> getMetadata(String name, int releaseYear, String language) throws TmdbException {
        log.debug("Starting task executing.");
        List<Movie> tvSeriesResultsPage = tmdbApi.getSearch().searchMovie(name, null, null, String.valueOf(releaseYear), null, null, null).getResults();
        if (!tvSeriesResultsPage.isEmpty()) {
            return Optional.of(getInfoForShow(tvSeriesResultsPage.get(0), language));
        }
        return Optional.empty();
    }

    private TMDBResult getInfoForShow(Movie movieResultsPage, String language) {
        try {
            MovieDb movieDb = tmdbApi.getMovies().getDetails(movieResultsPage.getId(), language);
            return TMDBResult.builder()
                    .language(Locale.forLanguageTag(language).getISO3Language())
                    .title(movieDb.getTitle())
                    .released(LocalDate.parse(movieDb.getReleaseDate()))
                    .sourceUri("TMDB://" + movieDb.getId())
                    .description(movieDb.getOverview().trim().isEmpty() ? null : movieDb.getOverview())
                    .posterUrl(movieDb.getPosterPath() == null ? null : "https://image.tmdb.org/t/p/original" + movieDb.getPosterPath())
                    .backgroundUrl(movieDb.getBackdropPath() == null ? null : "https://image.tmdb.org/t/p/original" + movieDb.getBackdropPath())
                    .build();
        } catch (TmdbException e) {
            throw new RuntimeException(e);
        }
    }
}
