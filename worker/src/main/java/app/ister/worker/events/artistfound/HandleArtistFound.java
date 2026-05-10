package app.ister.worker.events.artistfound;

import app.ister.core.Handle;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.ArtistFoundData;
import app.ister.core.repository.ArtistRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.worker.events.musicbrainz.MusicBrainzService;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.tmdbmetadata.ImageSave;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_ARTIST_FOUND;

@Slf4j
@Service("workerHandleArtistFound")
@Transactional
@RequiredArgsConstructor
public class HandleArtistFound implements Handle<ArtistFoundData> {

    private final ArtistRepository artistRepository;
    private final ImageRepository imageRepository;
    private final MetadataRepository metadataRepository;
    private final MusicBrainzService musicBrainzService;
    private final ImageDownloadService imageDownloadService;

    @Override
    public EventType handles() {
        return EventType.ARTIST_FOUND;
    }

    @RabbitListener(queues = APP_ISTER_SERVER_ARTIST_FOUND)
    @Override
    public void listener(ArtistFoundData data) {
        Handle.super.listener(data);
    }

    @Override
    public Boolean handle(ArtistFoundData data) {
        artistRepository.findById(data.getArtistId()).ifPresent(artist -> {
            boolean hasMetadata = !metadataRepository.findByArtistEntityId(artist.getId()).isEmpty();
            boolean hasImage = !imageRepository.findByArtistEntityId(artist.getId()).isEmpty();
            if (hasMetadata && hasImage) return;

            musicBrainzService.getArtistInfo(artist.getName()).ifPresent(info -> {
                if (!hasMetadata && info.bio() != null) {
                    metadataRepository.save(MetadataEntity.builder()
                            .description(info.bio())
                            .genre(info.genre())
                            .artistEntity(artist)
                            .sourceUri("musicbrainz://artist/" + artist.getName())
                            .build());
                }
                if (!hasImage && info.imageUrl() != null) {
                    try {
                        imageDownloadService.downloadAndSave(info.imageUrl(), ImageType.COVER, "eng",
                                "wikipedia://" + info.imageUrl(),
                                new ImageSave.MediaEntityRef(null, null, null, artist, null));
                    } catch (IOException e) {
                        log.warn("Failed to download artist image for artist={}: {}", artist.getName(), e.getMessage());
                    }
                }
            });
        });
        return true;
    }
}
