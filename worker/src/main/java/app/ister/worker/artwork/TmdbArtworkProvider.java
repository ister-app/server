package app.ister.worker.artwork;

import app.ister.core.artwork.ImageTarget;
import app.ister.core.artwork.RemoteArtworkProvider;
import app.ister.core.artwork.RemoteImageCandidate;
import app.ister.core.config.LanguageProperties;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.MetadataSource;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.tmdbapi.model.MovieImages200Response;
import app.ister.tmdbapi.model.PersonImages200Response;
import app.ister.tmdbapi.model.TvEpisodeImages200Response;
import app.ister.tmdbapi.model.TvSeriesImages200Response;
import app.ister.worker.clients.TmdbClient;
import app.ister.worker.events.tmdbmetadata.TmdbImageBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * TMDB's full image lists for movies, shows, episodes and people — the scan stores only the one
 * poster/backdrop the details call names. Candidates carry the same {@code TMDB://<original url>}
 * sourceUri the scan uses, so a stored one is recognised, and a {@code w500} preview URL for the
 * picker. Languages: the configured ones plus language-less images, which is what TMDB's own site
 * shows by default.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TmdbArtworkProvider implements RemoteArtworkProvider {

    static final String SCHEME = "TMDB://";
    private static final String PREVIEW_SIZE = "w500";

    private final TmdbClient tmdbClient;
    private final TmdbImageBase imageBase;
    private final LanguageProperties languageProperties;
    private final MovieRepository movieRepository;
    private final ShowRepository showRepository;
    private final EpisodeRepository episodeRepository;
    private final PersonRepository personRepository;

    @Override
    @Transactional(readOnly = true)
    public List<RemoteImageCandidate> candidates(ImageTarget target, ImageType type) {
        if (type != ImageType.COVER && type != ImageType.BACKGROUND) {
            return List.of();
        }
        try {
            return switch (target.kind()) {
                case MOVIE -> movieRepository.findById(target.id())
                        .map(m -> m.getTmdbId() == null ? List.<RemoteImageCandidate>of() : movieImages(m.getTmdbId(), type))
                        .orElse(List.of());
                case SHOW -> showRepository.findById(target.id())
                        .map(s -> s.getTmdbId() == null ? List.<RemoteImageCandidate>of() : showImages(s.getTmdbId(), type))
                        .orElse(List.of());
                case EPISODE -> episodeRepository.findById(target.id())
                        .map(this::episodeImages)
                        .orElse(List.of());
                case PERSON -> type == ImageType.COVER ? personCandidates(target) : List.of();
                default -> List.of();
            };
        } catch (RuntimeException e) {
            // A TMDB hiccup must not fail the picker: the stored images are still listed.
            log.warn("TMDB image lookup for {} failed: {}", target, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Optional<String> downloadUrl(String sourceUri) {
        if (sourceUri == null || !sourceUri.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return Optional.empty();
        }
        String url = sourceUri.substring(SCHEME.length());
        // Only TMDB's own image host: the sourceUri is admin input.
        return url.startsWith(imageBase.url("/")) ? Optional.of(url) : Optional.empty();
    }

    private List<RemoteImageCandidate> movieImages(int tmdbId, ImageType type) {
        MovieImages200Response body = tmdbClient._movieImages(tmdbId, includeLanguages(), null).getBody();
        if (body == null) {
            return List.of();
        }
        return type == ImageType.COVER
                ? body.getPosters().stream().map(p -> candidate(p.getFilePath(), p.getWidth(), p.getHeight(), p.getIso6391(), p.getVoteAverage())).toList()
                : body.getBackdrops().stream().map(b -> candidate(b.getFilePath(), b.getWidth(), b.getHeight(), b.getIso6391(), b.getVoteAverage())).toList();
    }

    private List<RemoteImageCandidate> showImages(int tmdbId, ImageType type) {
        TvSeriesImages200Response body = tmdbClient._tvSeriesImages(tmdbId, includeLanguages(), null).getBody();
        if (body == null) {
            return List.of();
        }
        return type == ImageType.COVER
                ? body.getPosters().stream().map(p -> candidate(p.getFilePath(), p.getWidth(), p.getHeight(), p.getIso6391(), p.getVoteAverage())).toList()
                : body.getBackdrops().stream().map(b -> candidate(b.getFilePath(), b.getWidth(), b.getHeight(), b.getIso6391(), b.getVoteAverage())).toList();
    }

    /** An episode has stills only; TMDB offers them for the background slot, and the scan stores them as COVER too. */
    private List<RemoteImageCandidate> episodeImages(EpisodeEntity episode) {
        Integer showTmdbId = episode.getShowEntity() == null ? null : episode.getShowEntity().getTmdbId();
        if (showTmdbId == null || episode.getSeasonEntity() == null) {
            return List.of();
        }
        TvEpisodeImages200Response body = tmdbClient._tvEpisodeImages(showTmdbId, episode.getSeasonEntity().getNumber(),
                episode.getNumber(), includeLanguages(), null).getBody();
        if (body == null) {
            return List.of();
        }
        return body.getStills().stream()
                .map(s -> candidate(s.getFilePath(), s.getWidth(), s.getHeight(), s.getIso6391(), s.getVoteAverage()))
                .toList();
    }

    private List<RemoteImageCandidate> personCandidates(ImageTarget target) {
        return personRepository.findById(target.id())
                .map(p -> p.getTmdbId() == null ? List.<RemoteImageCandidate>of() : personImages(p.getTmdbId().intValue()))
                .orElse(List.of());
    }

    private List<RemoteImageCandidate> personImages(int tmdbId) {
        PersonImages200Response body = tmdbClient._personImages(tmdbId).getBody();
        if (body == null) {
            return List.of();
        }
        return body.getProfiles().stream()
                .map(p -> candidate(p.getFilePath(), p.getWidth(), p.getHeight(), p.getIso6391(), p.getVoteAverage()))
                .toList();
    }

    private String includeLanguages() {
        List<String> tags = new ArrayList<>(languageProperties.tags());
        tags.add("null");
        return tags.stream().distinct().collect(Collectors.joining(","));
    }

    private RemoteImageCandidate candidate(@Nullable String filePath, Integer width, Integer height,
                                           @Nullable Object iso6391, @Nullable BigDecimal vote) {
        if (filePath == null) {
            return null;
        }
        String original = imageBase.url(filePath);
        return new RemoteImageCandidate(
                imageBase.url(filePath).replace("/original/", "/" + PREVIEW_SIZE + "/"),
                SCHEME + original,
                MetadataSource.TMDB,
                width, height,
                iso3Of(iso6391),
                vote == null ? null : vote.doubleValue());
    }

    /** Image.language is ISO 639-3; TMDB hands out 639-1 (or null for text-less artwork). */
    static @Nullable String iso3Of(@Nullable Object iso6391) {
        if (iso6391 == null || iso6391.toString().isBlank() || "null".equals(iso6391.toString())) {
            return null;
        }
        try {
            String iso3 = Locale.forLanguageTag(iso6391.toString()).getISO3Language();
            return iso3.isEmpty() ? null : iso3;
        } catch (RuntimeException _) {
            return null;
        }
    }

    /** Sorting helper for callers that want TMDB's own ranking: best vote first. */
    static Comparator<RemoteImageCandidate> byScore() {
        return Comparator.comparing((RemoteImageCandidate c) -> c.score() == null ? 0d : c.score()).reversed();
    }
}
