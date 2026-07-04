package app.ister.worker.events.tmdbmetadata;

import app.ister.core.entity.CreditEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.CreditType;
import app.ister.core.repository.CreditRepository;
import app.ister.tmdbapi.model.MovieCredits200Response;
import app.ister.tmdbapi.model.TvEpisodeCredits200Response;
import app.ister.tmdbapi.model.TvSeriesAggregateCredits200Response;
import app.ister.worker.clients.TmdbClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Fetches TMDB cast credits (actors only, crew is ignored) and stores them as
 * {@link CreditEntity} rows. Existing credits for the target entity are deleted first,
 * so re-running (e.g. on re-analyze) replaces instead of duplicates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditsService {
    private static final String LANGUAGE = "en-US";

    private final TmdbClient tmdbClient;
    private final CreditRepository creditRepository;
    private final PersonLookupService personLookupService;

    public void fetchForMovie(MovieEntity movie, int tmdbMovieId) {
        creditRepository.deleteByMovieEntityId(movie.getId());
        MovieCredits200Response credits = tmdbClient._movieCredits(tmdbMovieId, LANGUAGE).getBody();
        if (credits == null) {
            return;
        }
        credits.getCast().forEach(member -> {
            PersonEntity person = personLookupService.getOrCreateFromTmdb(member.getId(), member.getName(), member.getProfilePath());
            CreditEntity credit = CreditEntity.builder()
                    .personEntity(person)
                    .movieEntityId(movie.getId())
                    .characterName(member.getCharacter())
                    .creditType(CreditType.CAST)
                    .castOrder(member.getOrder())
                    .tmdbCreditId(member.getCreditId())
                    .build();
            creditRepository.save(credit);
        });
        log.debug("Saved {} cast credits for movie {}", credits.getCast().size(), movie.getName());
    }

    public void fetchForShow(ShowEntity show, int tmdbSeriesId) {
        creditRepository.deleteByShowEntityId(show.getId());
        TvSeriesAggregateCredits200Response credits = tmdbClient._tvSeriesAggregateCredits(tmdbSeriesId, LANGUAGE).getBody();
        if (credits == null) {
            return;
        }
        credits.getCast().forEach(member -> {
            PersonEntity person = personLookupService.getOrCreateFromTmdb(member.getId(), member.getName(), member.getProfilePath());
            member.getRoles().forEach(role -> {
                CreditEntity credit = CreditEntity.builder()
                        .personEntity(person)
                        .showEntityId(show.getId())
                        .characterName(role.getCharacter())
                        .creditType(CreditType.CAST)
                        .castOrder(member.getOrder())
                        .tmdbCreditId(role.getCreditId())
                        .build();
                creditRepository.save(credit);
            });
        });
        log.debug("Saved cast credits for show {} ({} cast members)", show.getName(), credits.getCast().size());
    }

    public void fetchForEpisode(EpisodeEntity episode, int tmdbSeriesId, int seasonNumber, int episodeNumber) {
        creditRepository.deleteByEpisodeEntityId(episode.getId());
        TvEpisodeCredits200Response credits = tmdbClient._tvEpisodeCredits(tmdbSeriesId, seasonNumber, episodeNumber, LANGUAGE).getBody();
        if (credits == null) {
            return;
        }
        credits.getCast().forEach(member -> {
            PersonEntity person = personLookupService.getOrCreateFromTmdb(member.getId(), member.getName(), member.getProfilePath());
            creditRepository.save(CreditEntity.builder()
                    .personEntity(person)
                    .episodeEntityId(episode.getId())
                    .characterName(member.getCharacter())
                    .creditType(CreditType.CAST)
                    .castOrder(member.getOrder())
                    .tmdbCreditId(member.getCreditId())
                    .build());
        });
        credits.getGuestStars().forEach(member -> {
            PersonEntity person = personLookupService.getOrCreateFromTmdb(member.getId(), member.getName(), member.getProfilePath());
            creditRepository.save(CreditEntity.builder()
                    .personEntity(person)
                    .episodeEntityId(episode.getId())
                    .characterName(member.getCharacter())
                    .creditType(CreditType.GUEST_STAR)
                    .castOrder(member.getOrder())
                    .tmdbCreditId(member.getCreditId())
                    .build());
        });
        log.debug("Saved {} cast and {} guest star credits for episode {}x{}",
                credits.getCast().size(), credits.getGuestStars().size(), seasonNumber, episodeNumber);
    }
}
