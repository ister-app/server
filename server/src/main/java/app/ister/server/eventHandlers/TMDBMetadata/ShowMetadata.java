package app.ister.server.eventHandlers.TMDBMetadata;

import info.movito.themoviedbapi.TmdbApi;
import info.movito.themoviedbapi.model.core.TvSeries;
import info.movito.themoviedbapi.model.tv.series.TvSeriesDb;
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
public class ShowMetadata {
    @Autowired
    private TmdbApi tmdbApi;

    public Optional<TMDBResult> getMetadata(String name, int releaseYear, String language) throws TmdbException {
        log.debug("Starting task executing.");
        List<TvSeries> tvSeriesResultsPage = tmdbApi.getSearch().searchTv(name, null, null, null, null, releaseYear).getResults();
        if (!tvSeriesResultsPage.isEmpty()) {
            return Optional.of(getInfoForShow(tvSeriesResultsPage.get(0), language));
        }
        return Optional.empty();
    }

    private TMDBResult getInfoForShow(TvSeries tvSeriesResultsPage, String language) {
        try {
            TvSeriesDb tvSeries1 = tmdbApi.getTvSeries().getDetails(tvSeriesResultsPage.getId(), language);
            return TMDBResult.builder()
                    .language(Locale.forLanguageTag(language).getISO3Language())
                    .title(tvSeries1.getName())
                    .released(LocalDate.parse(tvSeries1.getFirstAirDate()))
                    .sourceUri("TMDB://" + tvSeries1.getId())
                    .description(tvSeries1.getOverview().trim().isEmpty() ? null : tvSeries1.getOverview())
                    .posterUrl(tvSeries1.getPosterPath() == null ? null : "https://image.tmdb.org/t/p/original" + tvSeries1.getPosterPath())
                    .backgroundUrl(tvSeries1.getBackdropPath() == null ? null : "https://image.tmdb.org/t/p/original" + tvSeries1.getBackdropPath())
                    .build();
        } catch (TmdbException e) {
            throw new RuntimeException(e);
        }
    }
}
