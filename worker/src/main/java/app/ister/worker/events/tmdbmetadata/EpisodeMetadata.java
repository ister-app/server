package app.ister.worker.events.tmdbmetadata;

import app.ister.tmdbapi.model.SearchTv200Response;
import app.ister.tmdbapi.model.SearchTv200ResponseResultsInner;
import app.ister.tmdbapi.model.TvEpisodeDetails200Response;
import app.ister.worker.clients.TmdbClient;
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
    private final TmdbResultSelector resultSelector;

    /**
     * The movie database will give a default name if none is specified, for example "Episode 1".
     * To not store a default name in the database. This should be checked.
     */
    private final Map<String, String> noTitleSetMap = Map.of(
            "en", "Episode %d",
            "nl", "Aflevering %d");

    public EpisodeMetadata(TmdbClient tmdbClient, TmdbResultSelector resultSelector) {
        this.tmdbClient = tmdbClient;
        this.resultSelector = resultSelector;
    }

    public Optional<TMDBResult> getMetadata(String showName, int releaseYear, int seasonNumber, int episodeNumber, String language) throws FeignException {
        log.debug("Getting metadate from tmdb for showName: {}, releaseYear: {}, seasonNumber: {}, episodeNumber: {}, language: {}", showName, releaseYear, seasonNumber, episodeNumber, language);
        SearchTv200Response tvSeriesResultsPage = tmdbClient._searchTv(showName, null, null, null, null, releaseYear).getBody();
        if (tvSeriesResultsPage != null) {
            return resultSelector.selectTv(tvSeriesResultsPage.getResults(), showName)
                    .flatMap(result -> getMetadataForEpisode(result, seasonNumber, episodeNumber, language));
        } else {
            return Optional.empty();
        }
    }

    private Optional<TMDBResult> getMetadataForEpisode(@Valid SearchTv200ResponseResultsInner tvSeriesResultsPage, int seasonNumber, int episodeNumber, String language) throws FeignException {
        TvEpisodeDetails200Response episode;
        try {
            episode = tmdbClient._tvEpisodeDetails(tvSeriesResultsPage.getId(), seasonNumber, episodeNumber, "", language).getBody();
        } catch (FeignException.NotFound _) {
            // TMDB simply does not have this episode (specials, mis-numbered episodes, ...). Treat it
            // like any other "no metadata" case instead of letting the 404 dead-letter the message.
            log.debug("TMDB has no episode s{}e{} for series {}", seasonNumber, episodeNumber, tvSeriesResultsPage.getId());
            return Optional.empty();
        }
        if (episode != null && episode.getAirDate() != null && episode.getOverview() != null) {
            return Optional.of(TMDBResult.builder()
                    .language(Locale.forLanguageTag(language).getISO3Language())
                    .title(episodeTitle(episode.getName(), episode.getEpisodeNumber(), language))
                    .released(LocalDate.parse(episode.getAirDate()))
                    .sourceUri("TMDB://" + episode.getId())
                    .tmdbId(episode.getId())
                    .seriesTmdbId(tvSeriesResultsPage.getId())
                    .description(episode.getOverview().trim().isEmpty() ? null : episode.getOverview())
                    .backgroundUrl(episode.getStillPath() == null ? null : "https://image.tmdb.org/t/p/original" + episode.getStillPath())
                    .build());
        } else {
            log.debug("Couldn't find Episode {} {} {}", seasonNumber, episodeNumber, language);
            return Optional.empty();
        }
    }

    /**
     * Nulls out TMDB's placeholder episode names (e.g. "Episode 5", "Aflevering 5") so they are not
     * stored as real titles. For languages whose placeholder pattern we don't know the name is kept
     * as-is rather than failing.
     */
    private String episodeTitle(String name, int episodeNumber, String language) {
        String placeholderPattern = noTitleSetMap.get(language);
        if (placeholderPattern != null && String.format(placeholderPattern, episodeNumber).equals(name)) {
            return null;
        }
        return name;
    }
}
