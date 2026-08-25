package app.ister.worker.events.tmdbmetadata;

import app.ister.tmdbapi.model.SearchTv200Response;
import app.ister.tmdbapi.model.SearchTv200ResponseResultsInner;
import app.ister.tmdbapi.model.TvSeriesDetails200Response;
import app.ister.tmdbapi.model.TvSeriesDetails200ResponseGenresInner;
import app.ister.tmdbapi.model.TvSeriesDetails200ResponseNetworksInner;
import app.ister.tmdbapi.model.TvSeriesDetails200ResponseProductionCompaniesInner;
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
public class ShowMetadata {
    private final TmdbClient tmdbClient;
    private final TmdbResultSelector resultSelector;
    private final TmdbImageBase tmdbImageBase;

    public Optional<TMDBResult> getMetadata(String name, int releaseYear, String language) {
        log.debug("Starting task executing.");
        SearchTv200Response tvSeriesResultsPage = tmdbClient._searchTv(name, null, null, null, null, releaseYear).getBody();
        if (tvSeriesResultsPage != null) {
            return resultSelector.selectTv(tvSeriesResultsPage.getResults(), name)
                    .flatMap(result -> getInfoForShow(result, language));
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
                    .tmdbId(tvSeries1.getId())
                    .description(tvSeries1.getOverview().trim().isEmpty() ? null : tvSeries1.getOverview())
                    .posterUrl(tvSeries1.getPosterPath() == null ? null : tmdbImageBase.url(tvSeries1.getPosterPath()))
                    .backgroundUrl(tvSeries1.getBackdropPath() == null ? null : tmdbImageBase.url(tvSeries1.getBackdropPath()))
                    .tagline(TmdbFieldUtil.blankToNull(tvSeries1.getTagline()))
                    .genres(TmdbFieldUtil.joinNonBlank(tvSeries1.getGenres(), TvSeriesDetails200ResponseGenresInner::getName))
                    .voteAverage(TmdbFieldUtil.withVotes(tvSeries1.getVoteCount(), tvSeries1.getVoteAverage()))
                    .voteCount(TmdbFieldUtil.positiveOrNull(tvSeries1.getVoteCount()))
                    .status(TmdbFieldUtil.blankToNull(tvSeries1.getStatus()))
                    .homepage(TmdbFieldUtil.blankToNull(tvSeries1.getHomepage()))
                    .networks(TmdbFieldUtil.joinNonBlank(tvSeries1.getNetworks(), TvSeriesDetails200ResponseNetworksInner::getName))
                    .studios(TmdbFieldUtil.joinNonBlank(tvSeries1.getProductionCompanies(), TvSeriesDetails200ResponseProductionCompaniesInner::getName))
                    .originCountry(TmdbFieldUtil.joinNonBlank(tvSeries1.getOriginCountry(), country -> country))
                    .build());
        } else {
            log.debug("Couldn't find Show {} {} {}", tvSeriesResultsPage.getName(), tvSeriesResultsPage.getFirstAirDate(), language);
            return Optional.empty();
        }
    }
}
