package app.ister.server.events.TMDBMetadata;

import app.ister.server.clients.TmdbClient;
import app.ister.tmdbapi.model.SearchTv200Response;
import app.ister.tmdbapi.model.SearchTv200ResponseResultsInner;
import app.ister.tmdbapi.model.TvSeriesDetails200Response;
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
public class ShowMetadata {
    @Autowired
    private TmdbClient tmdbClient;

    public Optional<TMDBResult> getMetadata(String name, int releaseYear, String language) {
        log.debug("Starting task executing.");
        SearchTv200Response tvSeriesResultsPage = tmdbClient._searchTv(name, null, null, null, null, releaseYear).getBody();
        if (tvSeriesResultsPage != null && !tvSeriesResultsPage.getResults().isEmpty()) {
            return getInfoForShow(tvSeriesResultsPage.getResults().getFirst(), language);
        }
        return Optional.empty();
    }

    private Optional<TMDBResult> getInfoForShow(@Valid SearchTv200ResponseResultsInner tvSeriesResultsPage, String language) throws FeignException {
        TvSeriesDetails200Response tvSeries1 = tmdbClient._tvSeriesDetails(tvSeriesResultsPage.getId(), "", language).getBody();
        if (tvSeries1 != null && tvSeries1.getFirstAirDate() != null && tvSeries1.getOverview() != null) {
            return Optional.of(TMDBResult.builder()
                    .language(Locale.forLanguageTag(language).getISO3Language())
                    .title(tvSeries1.getName())
                    .released(LocalDate.parse(tvSeries1.getFirstAirDate()))
                    .sourceUri("TMDB://" + tvSeries1.getId())
                    .description(tvSeries1.getOverview().trim().isEmpty() ? null : tvSeries1.getOverview())
                    .posterUrl(tvSeries1.getPosterPath() == null ? null : "https://image.tmdb.org/t/p/original" + tvSeries1.getPosterPath())
                    .backgroundUrl(tvSeries1.getBackdropPath() == null ? null : "https://image.tmdb.org/t/p/original" + tvSeries1.getBackdropPath())
                    .build());
        } else {
            log.debug("Couldn't find Show {} {} {}", tvSeriesResultsPage.getName(), tvSeriesResultsPage.getFirstAirDate(), language);
            return Optional.empty();
        }
    }
}
