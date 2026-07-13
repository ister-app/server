package app.ister.worker.events.personfound;

import app.ister.core.Handle;
import app.ister.core.config.LanguageProperties;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.PersonFoundData;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.service.ServerEventService;
import app.ister.worker.events.musicbrainz.MusicBrainzService;
import app.ister.worker.events.openlibrary.OpenLibraryService;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.tmdbmetadata.ImageSave;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Map;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_PERSON_FOUND;

/**
 * Enriches a person with a biography (one per configured language), a birth year and a portrait.
 * The source depends on what the person is: book authors come from Open Library, everyone else
 * (music artists, and TMDB-only persons without a library) from MusicBrainz. Both fall back to
 * Wikipedia for the bios and the portrait.
 */
@Slf4j
@Service("workerHandlePersonFound")
@Transactional
@RequiredArgsConstructor
public class HandlePersonFound implements Handle<PersonFoundData> {

    private final PersonRepository personRepository;
    private final ImageRepository imageRepository;
    private final MetadataRepository metadataRepository;
    private final MusicBrainzService musicBrainzService;
    private final OpenLibraryService openLibraryService;
    private final ImageDownloadService imageDownloadService;
    private final ServerEventService serverEventService;
    private final LanguageProperties languageProperties;

    @Override
    public EventType handles() {
        return EventType.PERSON_FOUND;
    }

    @RabbitListener(queues = APP_ISTER_SERVER_PERSON_FOUND)
    @Override
    public void listener(PersonFoundData data) {
        Handle.super.listener(data);
    }

    @Override
    public void handle(PersonFoundData data) {
        personRepository.findById(data.getPersonId()).ifPresent(person -> {
            boolean hasMetadata = !metadataRepository.findByPersonEntityId(person.getId()).isEmpty();
            boolean hasImage = !imageRepository.findByPersonEntityId(person.getId()).isEmpty();
            boolean needsBirthYear = person.getBirthYear() == null;
            // Still call the metadata source when the birth year is missing even if metadata+image
            // exist: that year is what links a music artist to the same person as a TMDB actor.
            if (hasMetadata && hasImage && !needsBirthYear) return;

            if (isAuthor(person)) {
                openLibraryService.getAuthorInfo(person.getName(), languageProperties.tags())
                        .ifPresent(info -> enrichAuthor(person, info, hasMetadata, hasImage));
            } else {
                musicBrainzService.getArtistInfo(person.getName(), languageProperties.tags())
                        .ifPresent(info -> enrichArtist(person, info, hasMetadata, hasImage));
            }
        });
    }

    private boolean isAuthor(PersonEntity person) {
        return person.getLibraryEntity() != null
                && person.getLibraryEntity().getLibraryType() == LibraryType.BOOK;
    }

    private void enrichAuthor(PersonEntity author, OpenLibraryService.AuthorInfo info,
                              boolean hasMetadata, boolean hasImage) {
        String sourceUri = "openlibrary://author/" + info.sourceKey();
        if (author.getBirthYear() == null && info.birthYear() != null) {
            author.setBirthYear(info.birthYear());
            personRepository.save(author);
        }
        if (!hasMetadata && !info.bios().isEmpty()) {
            saveBiosPerLanguage(author, info.bios(), null, sourceUri);
        }
        if (!hasImage && info.photoUrl() != null) {
            savePersonImage(author, info.photoUrl(), sourceUri);
        }
        serverEventService.createSearchIndexEvent(SearchEntityType.PERSON, author.getId());
    }

    private void enrichArtist(PersonEntity artist, MusicBrainzService.ArtistInfo info,
                              boolean hasMetadata, boolean hasImage) {
        saveBirthYear(artist, info);
        if (!hasMetadata && !info.bios().isEmpty()) {
            saveBiosPerLanguage(artist, info.bios(), info.genre(), "musicbrainz://artist/" + artist.getName());
        }
        if (!hasImage && info.imageUrl() != null) {
            savePersonImage(artist, info.imageUrl(), "wikipedia://" + info.imageUrl());
        }
        serverEventService.createSearchIndexEvent(SearchEntityType.PERSON, artist.getId());
    }

    /** Only individual persons get a birth year; groups keep NULL so a TMDB actor with the same
     * name can still claim/enrich the record. */
    private void saveBirthYear(PersonEntity artist, MusicBrainzService.ArtistInfo info) {
        if (artist.getBirthYear() != null || !"Person".equals(info.type())
                || info.lifeSpanBegin() == null || info.lifeSpanBegin().length() < 4) {
            return;
        }
        try {
            artist.setBirthYear(Integer.parseInt(info.lifeSpanBegin().substring(0, 4)));
            personRepository.save(artist);
        } catch (NumberFormatException _) {
            log.debug("Unparsable life-span begin '{}' for artist={}", info.lifeSpanBegin(), artist.getName());
        }
    }

    private void savePersonImage(PersonEntity person, String imageUrl, String sourceUri) {
        try {
            imageDownloadService.downloadAndSave(imageUrl, ImageType.COVER, "eng", sourceUri,
                    new ImageSave.MediaEntityRef(null, null, null, person, null));
        } catch (IOException e) {
            log.warn("Failed to download image for person={}: {}", person.getName(), e.getMessage());
        }
    }

    /** One MetadataEntity per configured language (ISO-639-3), replacing any existing rows so a
     * re-fetch overwrites instead of duplicating. */
    private void saveBiosPerLanguage(PersonEntity person, Map<String, String> bios, String genre, String sourceUri) {
        metadataRepository.deleteAll(metadataRepository.findByPersonEntityId(person.getId()));
        bios.forEach((tag, bio) -> metadataRepository.save(MetadataEntity.builder()
                .description(bio)
                .genre(genre)
                .language(languageProperties.iso3(tag))
                .personEntity(person)
                .sourceUri(sourceUri)
                .build()));
    }
}
