package app.ister.server.events.TMDBMetadata;

import app.ister.server.clients.TmdbClient;
import app.ister.tmdbapi.model.SearchTv200Response;
import app.ister.tmdbapi.model.SearchTv200ResponseResultsInner;
import app.ister.tmdbapi.model.TvEpisodeDetails200Response;
import feign.FeignException;
import jakarta.validation.Valid;
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
public class EpisodeMetadata {
    private final TmdbClient tmdbClient;

    /**
     * The movie database will give a default name if none is specified, for example "Episode 1".
     * To not store a default name in the database. This should be checked.
     */
    private final Map<String, String> noTitleSetMap = Map.of(
            "en", "Episode %d",
            "nl", "Aflevering %d");

    public EpisodeMetadata(TmdbClient tmdbClient) {
        this.tmdbClient = tmdbClient;
    }

    public Optional<TMDBResult> getMetadata(String showName, int releaseYear, int seasonNumber, int episodeNumber, String language) throws FeignException {
        log.debug("Getting metadate from tmdb for showName: {}, releaseYear: {}, seasonNumber: {}, episodeNumber: {}, language: {}", showName, releaseYear, seasonNumber, episodeNumber, language);
        SearchTv200Response tvSeriesResultsPage = tmdbClient._searchTv(showName, null, null, null, null, releaseYear).getBody();
        if (tvSeriesResultsPage != null && !tvSeriesResultsPage.getResults().isEmpty()) {
            return getMetadataForEpisode(tvSeriesResultsPage.getResults().getFirst(), seasonNumber, episodeNumber, language);
        } else {
            return Optional.empty();
        }
    }

    private Optional<TMDBResult> getMetadataForEpisode(@Valid SearchTv200ResponseResultsInner tvSeriesResultsPage, int seasonNumber, int episodeNumber, String language) throws FeignException {
        TvEpisodeDetails200Response episode = tmdbClient._tvEpisodeDetails(tvSeriesResultsPage.getId(), seasonNumber, episodeNumber, "", language).getBody();
        if (episode != null && episode.getAirDate() != null && episode.getOverview() != null) {
            return Optional.of(TMDBResult.builder()
                    .language(Locale.forLanguageTag(language).getISO3Language())
                    .title(String.format(noTitleSetMap.get(language), episode.getEpisodeNumber()).equals(episode.getName()) ? null : episode.getName())
                    .released(LocalDate.parse(episode.getAirDate()))
                    .sourceUri("TMDB://" + episode.getId())
                    .description(episode.getOverview().trim().isEmpty() ? null : episode.getOverview())
                    .backgroundUrl(episode.getStillPath() == null ? null : "https://image.tmdb.org/t/p/original" + episode.getStillPath())
                    .build());
        } else {
            log.debug("Couldn't find Episode {} {} {}", seasonNumber, episodeNumber, language);
            return Optional.empty();
        }
    }
}
