package app.ister.server.events.TMDBMetadata;

import info.movito.themoviedbapi.TmdbApi;
import info.movito.themoviedbapi.model.core.TvSeries;
import info.movito.themoviedbapi.model.tv.episode.TvEpisodeDb;
import info.movito.themoviedbapi.tools.TmdbException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
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
public class EpisodeMetadata {
    private final TmdbApi tmdbApi;

    /**
     * The movie database will give a default name if none is specified, for example "Episode 1".
     * To not store a default name in the database. This should be checked.
     */
    private final Map<String, String> noTitleSetMap = Map.of(
            "en", "Episode %d",
            "nl", "Aflevering %d");

    public EpisodeMetadata(TmdbApi tmdbApi) {
        this.tmdbApi = tmdbApi;
    }

    public Optional<TMDBResult> getMetadata(String showName, int releaseYear, int seasonNumber, int episodeNumber, String language) throws TmdbException {
        log.debug("Getting metadate from tmdb for showName: {}, releaseYear: {}, seasonNumber: {}, episodeNumber: {}, language: {}", showName, releaseYear, seasonNumber, episodeNumber, language);
        List<TvSeries> tvSeriesResultsPage = tmdbApi.getSearch().searchTv(showName, null, null, null, null, releaseYear).getResults();
        if (!tvSeriesResultsPage.isEmpty()) {
            return Optional.of(getMetadataForEpisode(tvSeriesResultsPage.get(0), seasonNumber, episodeNumber, language));
        } else {
            return Optional.empty();
        }
    }

    private TMDBResult getMetadataForEpisode(TvSeries tvSeriesResultsPage, int seasonNumber, int episodeNumber, String language) throws TmdbException {
        TvEpisodeDb episode = tmdbApi.getTvEpisodes().getDetails(tvSeriesResultsPage.getId(), seasonNumber, episodeNumber, language);
        return TMDBResult.builder()
                .language(Locale.forLanguageTag(language).getISO3Language())
                .title(String.format(noTitleSetMap.get(language), episode.getEpisodeNumber()).equals(episode.getName()) ? null : episode.getName())
                .released(LocalDate.parse(episode.getAirDate()))
                .sourceUri("TMDB://" + episode.getId())
                .description(episode.getOverview().trim().isEmpty() ? null : episode.getOverview())
                .backgroundUrl(episode.getStillPath() == null ? null : "https://image.tmdb.org/t/p/original" + episode.getStillPath())
                .build();
    }
}
