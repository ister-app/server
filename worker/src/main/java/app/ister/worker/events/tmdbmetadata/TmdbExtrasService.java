package app.ister.worker.events.tmdbmetadata;

import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.tmdbapi.model.MovieKeywords200Response;
import app.ister.tmdbapi.model.MovieKeywords200ResponseKeywordsInner;
import app.ister.tmdbapi.model.MovieReleaseDates200Response;
import app.ister.tmdbapi.model.MovieReleaseDates200ResponseResultsInner;
import app.ister.tmdbapi.model.MovieReleaseDates200ResponseResultsInnerReleaseDatesInner;
import app.ister.tmdbapi.model.MovieVideos200Response;
import app.ister.tmdbapi.model.TvSeriesContentRatings200Response;
import app.ister.tmdbapi.model.TvSeriesContentRatings200ResponseResultsInner;
import app.ister.tmdbapi.model.TvSeriesExternalIds200Response;
import app.ister.tmdbapi.model.TvSeriesKeywords200Response;
import app.ister.tmdbapi.model.TvSeriesKeywords200ResponseResultsInner;
import app.ister.tmdbapi.model.TvSeriesVideos200Response;
import app.ister.worker.clients.TmdbClient;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Fetches the language-independent TMDB extras (certification, trailer, keywords, external ids)
 * that are not part of the details response, and applies them to the movie/show entity.
 * Every call degrades to "field stays null" on failure: these are nice-to-haves and must never
 * dead-letter the surrounding metadata event (the chart e2e stubs TMDB with WireMock and fails
 * on any dead-letter).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TmdbExtrasService {
    /** Preferred country (ISO 3166-1) for certifications/content ratings; falls back to US, then any. */
    @Value("${app.ister.worker.tmdb.certification-country:US}")
    private String certificationCountry;

    private final TmdbClient tmdbClient;

    /** A video candidate normalized over the (structurally identical) movie/tv generated models. */
    private record Video(String key, String site, String type, boolean official) {
    }

    public void fetchForMovie(MovieEntity movie, int tmdbMovieId) {
        safeCall("movie release dates", () -> tmdbClient._movieReleaseDates(tmdbMovieId).getBody())
                .flatMap(this::movieCertification)
                .ifPresent(movie::setContentRating);
        safeCall("movie videos", () -> tmdbClient._movieVideos(tmdbMovieId, "en-US").getBody())
                .map(MovieVideos200Response::getResults)
                .flatMap(results -> selectTrailer(results.stream()
                        .map(video -> new Video(video.getKey(), video.getSite(), video.getType(), Boolean.TRUE.equals(video.getOfficial())))
                        .toList()))
                .ifPresent(trailer -> {
                    movie.setTrailerKey(trailer.key());
                    movie.setTrailerSite(trailer.site());
                });
        safeCall("movie keywords", () -> tmdbClient._movieKeywords(String.valueOf(tmdbMovieId)).getBody())
                .map(MovieKeywords200Response::getKeywords)
                .map(keywords -> TmdbFieldUtil.joinNonBlank(keywords, MovieKeywords200ResponseKeywordsInner::getName))
                .ifPresent(movie::setKeywords);
    }

    public void fetchForShow(ShowEntity show, int tmdbSeriesId) {
        safeCall("tv content ratings", () -> tmdbClient._tvSeriesContentRatings(tmdbSeriesId).getBody())
                .flatMap(this::tvContentRating)
                .ifPresent(show::setContentRating);
        safeCall("tv external ids", () -> tmdbClient._tvSeriesExternalIds(tmdbSeriesId).getBody())
                .map(TvSeriesExternalIds200Response::getImdbId)
                .map(TmdbFieldUtil::blankToNull)
                .ifPresent(show::setImdbId);
        safeCall("tv videos", () -> tmdbClient._tvSeriesVideos(tmdbSeriesId, null, "en-US").getBody())
                .map(TvSeriesVideos200Response::getResults)
                .flatMap(results -> selectTrailer(results.stream()
                        .map(video -> new Video(video.getKey(), video.getSite(), video.getType(), Boolean.TRUE.equals(video.getOfficial())))
                        .toList()))
                .ifPresent(trailer -> {
                    show.setTrailerKey(trailer.key());
                    show.setTrailerSite(trailer.site());
                });
        safeCall("tv keywords", () -> tmdbClient._tvSeriesKeywords(tmdbSeriesId).getBody())
                .map(TvSeriesKeywords200Response::getResults)
                .map(keywords -> TmdbFieldUtil.joinNonBlank(keywords, TvSeriesKeywords200ResponseResultsInner::getName))
                .ifPresent(show::setKeywords);
    }

    private <T> Optional<T> safeCall(String what, Supplier<T> call) {
        try {
            return Optional.ofNullable(call.get());
        } catch (FeignException e) {
            log.warn("Fetching TMDB {} failed, leaving field empty: {}", what, e.getMessage());
            return Optional.empty();
        }
    }

    /** Certification of the configured country, else US, else the first country that has one. */
    private Optional<String> movieCertification(MovieReleaseDates200Response releaseDates) {
        List<MovieReleaseDates200ResponseResultsInner> results = releaseDates.getResults();
        if (results == null) {
            return Optional.empty();
        }
        return countryPreference(results, MovieReleaseDates200ResponseResultsInner::getIso31661,
                result -> firstNonBlank(result.getReleaseDates() == null ? List.of() : result.getReleaseDates().stream()
                        .map(MovieReleaseDates200ResponseResultsInnerReleaseDatesInner::getCertification)
                        .toList()));
    }

    private Optional<String> tvContentRating(TvSeriesContentRatings200Response contentRatings) {
        List<TvSeriesContentRatings200ResponseResultsInner> results = contentRatings.getResults();
        if (results == null) {
            return Optional.empty();
        }
        return countryPreference(results, TvSeriesContentRatings200ResponseResultsInner::getIso31661,
                result -> firstNonBlank(List.of(result.getRating() == null ? "" : result.getRating())));
    }

    private <T> Optional<String> countryPreference(List<T> results,
                                                   Function<T, String> country,
                                                   Function<T, Optional<String>> certification) {
        for (String preferred : List.of(certificationCountry, "US")) {
            Optional<String> match = results.stream()
                    .filter(result -> preferred.equalsIgnoreCase(country.apply(result)))
                    .flatMap(result -> certification.apply(result).stream())
                    .findFirst();
            if (match.isPresent()) {
                return match;
            }
        }
        return results.stream().flatMap(result -> certification.apply(result).stream()).findFirst();
    }

    private Optional<String> firstNonBlank(List<String> values) {
        return values.stream().filter(value -> value != null && !value.isBlank()).findFirst();
    }

    /** YouTube only; prefer an official trailer, then any trailer, then a teaser. */
    private Optional<Video> selectTrailer(List<Video> videos) {
        return videos.stream()
                .filter(video -> "YouTube".equalsIgnoreCase(video.site()) && video.key() != null)
                .filter(video -> "Trailer".equalsIgnoreCase(video.type()) || "Teaser".equalsIgnoreCase(video.type()))
                .min(Comparator
                        .comparing((Video video) -> !"Trailer".equalsIgnoreCase(video.type()))
                        .thenComparing(video -> !video.official()));
    }
}
