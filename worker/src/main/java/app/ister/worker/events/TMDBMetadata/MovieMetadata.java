package app.ister.worker.events.TMDBMetadata;

import app.ister.tmdbapi.model.MovieDetails200Response;
import app.ister.tmdbapi.model.SearchMovie200Response;
import app.ister.tmdbapi.model.SearchMovie200ResponseResultsInner;
import app.ister.worker.clients.TmdbClient;
import feign.FeignException;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
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
    private TmdbClient tmdbClient;

    public Optional<TMDBResult> getMetadata(String name, int releaseYear, String language) {
        log.debug("Starting task executing.");
        SearchMovie200Response tvSeriesResultsPage = tmdbClient._searchMovie(name, null, null, String.valueOf(releaseYear), null, null, null).getBody();
        if (tvSeriesResultsPage != null && !tvSeriesResultsPage.getResults().isEmpty()) {
            return getInfoForShow(tvSeriesResultsPage.getResults().getFirst(), language);
        }
        return Optional.empty();
    }

    private Optional<TMDBResult> getInfoForShow(@Valid SearchMovie200ResponseResultsInner movieResultsPage, String language) throws FeignException {
        MovieDetails200Response movieDb = tmdbClient._movieDetails(movieResultsPage.getId(), "", language).getBody();
        if (movieDb != null && movieDb.getReleaseDate() != null && movieDb.getOverview() != null) {
            return Optional.of(TMDBResult.builder()
                    .language(Locale.forLanguageTag(language).getISO3Language())
                    .title(movieDb.getTitle())
                    .released(LocalDate.parse(movieDb.getReleaseDate()))
                    .sourceUri("TMDB://" + movieDb.getId())
                    .description(movieDb.getOverview().trim().isEmpty() ? null : movieDb.getOverview())
                    .posterUrl(movieDb.getPosterPath() == null ? null : "https://image.tmdb.org/t/p/original" + movieDb.getPosterPath())
                    .backgroundUrl(movieDb.getBackdropPath() == null ? null : "https://image.tmdb.org/t/p/original" + movieDb.getBackdropPath())
                    .build());
        } else {
            log.debug("Couldn't find Movie {} {} {}", movieResultsPage.getTitle(), movieResultsPage.getReleaseDate(), language);
            return Optional.empty();
        }
    }
}
