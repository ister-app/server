package app.ister.worker.events.tmdbmetadata;

import app.ister.tmdbapi.model.MovieDetails200Response;
import app.ister.tmdbapi.model.SearchMovie200Response;
import app.ister.tmdbapi.model.SearchMovie200ResponseResultsInner;
import app.ister.worker.clients.TmdbClient;
import feign.FeignException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class MovieMetadata {
    private final TmdbClient tmdbClient;
    private final TmdbResultSelector resultSelector;
    private final TmdbImageBase tmdbImageBase;

    public Optional<TMDBResult> getMetadata(String name, int releaseYear, String language) {
        log.debug("Starting task executing.");
        SearchMovie200Response tvSeriesResultsPage = tmdbClient._searchMovie(name, null, null, String.valueOf(releaseYear), null, null, null).getBody();
        if (tvSeriesResultsPage != null) {
            return resultSelector.selectMovie(tvSeriesResultsPage.getResults(), name)
                    .flatMap(result -> getInfoForShow(result, language));
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
                    .tmdbId(movieDb.getId())
                    .description(movieDb.getOverview().trim().isEmpty() ? null : movieDb.getOverview())
                    .posterUrl(movieDb.getPosterPath() == null ? null : tmdbImageBase.url(movieDb.getPosterPath()))
                    .backgroundUrl(movieDb.getBackdropPath() == null ? null : tmdbImageBase.url(movieDb.getBackdropPath()))
                    .build());
        } else {
            log.debug("Couldn't find Movie {} {} {}", movieResultsPage.getTitle(), movieResultsPage.getReleaseDate(), language);
            return Optional.empty();
        }
    }
}
