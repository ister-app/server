package app.ister.worker.events.personfound;

import app.ister.core.Handle;
import app.ister.core.config.LanguageProperties;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.PersonFoundData;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.service.ServerEventService;
import app.ister.worker.events.musicbrainz.MusicBrainzService;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.tmdbmetadata.ImageSave;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_PERSON_FOUND;

@Slf4j
@Service("workerHandlePersonFound")
@Transactional
@RequiredArgsConstructor
public class HandlePersonFound implements Handle<PersonFoundData> {

    private final PersonRepository personRepository;
    private final ImageRepository imageRepository;
    private final MetadataRepository metadataRepository;
    private final MusicBrainzService musicBrainzService;
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
        personRepository.findById(data.getPersonId()).ifPresent(artist -> {
            boolean hasMetadata = !metadataRepository.findByPersonEntityId(artist.getId()).isEmpty();
            boolean hasImage = !imageRepository.findByPersonEntityId(artist.getId()).isEmpty();
            boolean needsBirthYear = artist.getBirthYear() == null;
            // Still call MusicBrainz when the birth year is missing even if metadata+image exist:
            // that year is what links a music artist to the same person as a TMDB actor.
            if (hasMetadata && hasImage && !needsBirthYear) return;

            musicBrainzService.getArtistInfo(artist.getName(), languageProperties.tags())
                    .ifPresent(info -> enrichArtist(artist, info, hasMetadata, hasImage));
        });
    }

    private void enrichArtist(PersonEntity artist, MusicBrainzService.ArtistInfo info,
                              boolean hasMetadata, boolean hasImage) {
        saveBirthYear(artist, info);
        if (!hasMetadata && !info.bios().isEmpty()) {
            saveBiosPerLanguage(artist, info);
        }
        if (!hasImage && info.imageUrl() != null) {
            saveArtistImage(artist, info.imageUrl());
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

    private void saveArtistImage(PersonEntity artist, String imageUrl) {
        try {
            imageDownloadService.downloadAndSave(imageUrl, ImageType.COVER, "eng",
                    "wikipedia://" + imageUrl,
                    new ImageSave.MediaEntityRef(null, null, null, artist, null));
        } catch (IOException e) {
            log.warn("Failed to download artist image for artist={}: {}", artist.getName(), e.getMessage());
        }
    }

    /** One MetadataEntity per configured language (ISO-639-3), replacing any existing rows so a
     * re-fetch overwrites instead of duplicating. */
    private void saveBiosPerLanguage(PersonEntity artist, MusicBrainzService.ArtistInfo info) {
        metadataRepository.deleteAll(metadataRepository.findByPersonEntityId(artist.getId()));
        info.bios().forEach((tag, bio) -> metadataRepository.save(MetadataEntity.builder()
                .description(bio)
                .genre(info.genre())
                .language(languageProperties.iso3(tag))
                .personEntity(artist)
                .sourceUri("musicbrainz://artist/" + artist.getName())
                .build()));
    }
}
