package app.ister.server.eventHandlers.TMDBMetadata;

import info.movito.themoviedbapi.TmdbApi;
import info.movito.themoviedbapi.model.core.TvSeries;
import info.movito.themoviedbapi.model.tv.episode.TvEpisodeDb;
import info.movito.themoviedbapi.tools.TmdbException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired
    private TmdbApi tmdbApi;

    /**
     * The movie database will give a default name if none is specified, for example "Episode 1".
     * To not store a default name in the database. This should be checked.
     */
    private final Map<String, String> noTitleSetMap = Map.of(
            "en", "Episode %d",
            "nl", "Aflevering %d");

    public Optional<TMDBResult> getMetadata(String showName, int releaseYear, int seasonNumber, int episodeNumber, String language) throws TmdbException {
        log.debug("Starting task executing.");
        List<TvSeries> tvSeriesResultsPage = tmdbApi.getSearch().searchTv(showName, null, null, null, null, releaseYear).getResults();
        if (!tvSeriesResultsPage.isEmpty()) {
            return Optional.of(getMetadataForEpisode(tvSeriesResultsPage.get(0), seasonNumber, episodeNumber, language));
        }
        return Optional.empty();
    }

    private TMDBResult getMetadataForEpisode(TvSeries tvSeriesResultsPage, int seasonNumber, int episodeNumber, String language) {
        try {
            TvEpisodeDb episode = tmdbApi.getTvEpisodes().getDetails(tvSeriesResultsPage.getId(), seasonNumber, episodeNumber, language);
            return TMDBResult.builder()
                    .language(Locale.forLanguageTag(language).getISO3Language())
                    .title(String.format(noTitleSetMap.get(language), episode.getEpisodeNumber()).equals(episode.getName()) ? null : episode.getName())
                    .released(LocalDate.parse(episode.getAirDate()))
                    .sourceUri("TMDB://" + episode.getId())
                    .description(episode.getOverview().trim().isEmpty() ? null : episode.getOverview())
                    .backgroundUrl(episode.getStillPath() == null ? null : "https://image.tmdb.org/t/p/original" + episode.getStillPath())
                    .build();
        } catch (TmdbException e) {
            throw new RuntimeException(e);
        }
    }
}
