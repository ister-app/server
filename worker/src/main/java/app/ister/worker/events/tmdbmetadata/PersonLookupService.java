package app.ister.worker.events.tmdbmetadata;

import app.ister.core.entity.PersonEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.service.ServerEventService;
import app.ister.tmdbapi.model.PersonDetails200Response;
import app.ister.worker.clients.TmdbClient;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;

/**
 * Finds or creates the {@link PersonEntity} for a TMDB cast member.
 *
 * Deduplication order:
 * 1. By TMDB id (cheap, no extra TMDB call).
 * 2. By exact name + birth year (requires a person-details call for the birthday).
 * 3. By exact name with unknown birth year: such a record (e.g. a MusicBrainz artist that
 *    was never enriched) is claimed and enriched with the TMDB id and birth year. This can
 *    falsely merge a band with an identically named actor — accepted trade-off.
 * 4. Otherwise a new library-less person is created.
 *
 * The create/enrich step runs in its own transaction so a unique-constraint race with a
 * concurrently running handler only rolls back that step; the winner is then re-queried.
 */
@Slf4j
@Service
public class PersonLookupService {
    private final PersonRepository personRepository;
    private final ImageRepository imageRepository;
    private final ImageDownloadService imageDownloadService;
    private final TmdbClient tmdbClient;
    private final TmdbImageBase tmdbImageBase;
    private final ServerEventService serverEventService;
    private final TransactionTemplate newTransaction;

    public PersonLookupService(PersonRepository personRepository,
                               ImageRepository imageRepository,
                               ImageDownloadService imageDownloadService,
                               TmdbClient tmdbClient,
                               TmdbImageBase tmdbImageBase,
                               ServerEventService serverEventService,
                               PlatformTransactionManager transactionManager) {
        this.personRepository = personRepository;
        this.imageRepository = imageRepository;
        this.imageDownloadService = imageDownloadService;
        this.tmdbClient = tmdbClient;
        this.tmdbImageBase = tmdbImageBase;
        this.serverEventService = serverEventService;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public PersonEntity getOrCreateFromTmdb(long tmdbPersonId, String name, @Nullable String profilePath) {
        PersonEntity person = personRepository.findByTmdbId(tmdbPersonId)
                .orElseGet(() -> findByNameAndBirthYearOrCreate(tmdbPersonId, name));
        downloadProfileImageIfMissing(person, profilePath);
        return person;
    }

    private PersonEntity findByNameAndBirthYearOrCreate(long tmdbPersonId, String name) {
        Integer birthYear = fetchBirthYear(tmdbPersonId);
        try {
            PersonEntity person = newTransaction.execute(status -> saveWithTmdbId(tmdbPersonId, name, birthYear));
            serverEventService.createSearchIndexEvent(SearchEntityType.PERSON, person.getId());
            return person;
        } catch (DataIntegrityViolationException e) {
            // A concurrent handler created this person first (unique tmdb_id index): use the winner.
            // The winner is committed by the time the constraint fires, so under READ_COMMITTED a
            // fresh read finds it. Re-query in its own transaction to guarantee a current snapshot
            // (the caller's outer transaction may hold an older one under stricter isolation) so a
            // transient miss never dead-letters the whole credits fetch.
            log.debug("Concurrent person creation for tmdbId={}, re-querying", tmdbPersonId);
            PersonEntity winner = newTransaction.execute(status ->
                    personRepository.findByTmdbId(tmdbPersonId).orElse(null));
            if (winner == null) {
                throw e;
            }
            return winner;
        }
    }

    private PersonEntity saveWithTmdbId(long tmdbPersonId, String name, @Nullable Integer birthYear) {
        PersonEntity person = null;
        if (birthYear != null) {
            person = personRepository.findByNameAndBirthYear(name, birthYear).stream().findFirst().orElse(null);
        }
        if (person == null) {
            person = personRepository.findByNameAndBirthYearIsNull(name).stream().findFirst().orElse(null);
        }
        if (person == null) {
            person = PersonEntity.builder().name(name).build();
        }
        person.setTmdbId(tmdbPersonId);
        if (person.getBirthYear() == null) {
            person.setBirthYear(birthYear);
        }
        return personRepository.saveAndFlush(person);
    }

    private @Nullable Integer fetchBirthYear(long tmdbPersonId) {
        PersonDetails200Response details = tmdbClient._personDetails((int) tmdbPersonId, null, "en-US").getBody();
        if (details == null || details.getBirthday() == null || details.getBirthday().length() < 4) {
            return null;
        }
        try {
            return Integer.parseInt(details.getBirthday().substring(0, 4));
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private void downloadProfileImageIfMissing(PersonEntity person, @Nullable String profilePath) {
        if (profilePath == null || !imageRepository.findByPersonEntityId(person.getId()).isEmpty()) {
            return;
        }
        try {
            imageDownloadService.downloadAndSave(tmdbImageBase.url(profilePath), ImageType.COVER, "eng",
                    "TMDB://" + profilePath,
                    new ImageSave.MediaEntityRef(null, null, null, person, null));
        } catch (IOException e) {
            log.warn("Failed to download profile image for person={}: {}", person.getName(), e.getMessage());
        }
    }
}
