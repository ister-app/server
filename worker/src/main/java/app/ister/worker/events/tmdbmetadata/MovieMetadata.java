package app.ister.worker.events.tmdbmetadata;

import app.ister.tmdbapi.model.MovieDetails200Response;
import app.ister.tmdbapi.model.MovieDetails200ResponseGenresInner;
import app.ister.tmdbapi.model.MovieDetails200ResponseProductionCompaniesInner;
import app.ister.tmdbapi.model.MovieDetails200ResponseProductionCountriesInner;
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
import java.util.Map;
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
                    .tagline(TmdbFieldUtil.blankToNull(movieDb.getTagline()))
                    .genres(TmdbFieldUtil.joinNonBlank(movieDb.getGenres(), MovieDetails200ResponseGenresInner::getName))
                    .runtime(TmdbFieldUtil.positiveOrNull(movieDb.getRuntime()))
                    .voteAverage(TmdbFieldUtil.withVotes(movieDb.getVoteCount(), movieDb.getVoteAverage()))
                    .voteCount(TmdbFieldUtil.positiveOrNull(movieDb.getVoteCount()))
                    .status(TmdbFieldUtil.blankToNull(movieDb.getStatus()))
                    .homepage(TmdbFieldUtil.blankToNull(movieDb.getHomepage()))
                    .imdbId(TmdbFieldUtil.blankToNull(movieDb.getImdbId()))
                    .collectionTmdbId(collectionField(movieDb.getBelongsToCollection(), "id", Number.class).map(Number::intValue).orElse(null))
                    .collectionName(collectionField(movieDb.getBelongsToCollection(), "name", String.class).orElse(null))
                    .studios(TmdbFieldUtil.joinNonBlank(movieDb.getProductionCompanies(), MovieDetails200ResponseProductionCompaniesInner::getName))
                    .originCountry(TmdbFieldUtil.joinNonBlank(movieDb.getProductionCountries(), MovieDetails200ResponseProductionCountriesInner::getIso31661))
                    .build());
        } else {
            log.debug("Couldn't find Movie {} {} {}", movieResultsPage.getTitle(), movieResultsPage.getReleaseDate(), language);
            return Optional.empty();
        }
    }

    /**
     * belongs_to_collection is typed as Object in the generated model (the spec declares it as a
     * free-form nullable object); at runtime Jackson deserializes it to a Map. Read defensively.
     */
    private <T> Optional<T> collectionField(Object belongsToCollection, String key, Class<T> type) {
        if (belongsToCollection instanceof Map<?, ?> map && type.isInstance(map.get(key))) {
            return Optional.of(type.cast(map.get(key)));
        }
        return Optional.empty();
    }
}
