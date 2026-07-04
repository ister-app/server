package app.ister.worker.events.albumfound;

import app.ister.core.Handle;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.AlbumFoundData;
import app.ister.core.repository.AlbumRepository;
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
import java.util.List;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_ALBUM_FOUND;

@Slf4j
@Service("workerHandleAlbumFound")
@Transactional
@RequiredArgsConstructor
public class HandleAlbumFound implements Handle<AlbumFoundData> {

    private final AlbumRepository albumRepository;
    private final ImageRepository imageRepository;
    private final MetadataRepository metadataRepository;
    private final MusicBrainzService musicBrainzService;
    private final ImageDownloadService imageDownloadService;

    @Override
    public EventType handles() {
        return EventType.ALBUM_FOUND;
    }

    @RabbitListener(queues = APP_ISTER_SERVER_ALBUM_FOUND)
    @Override
    public void listener(AlbumFoundData data) {
        Handle.super.listener(data);
    }

    @Override
    public void handle(AlbumFoundData data) {
        albumRepository.findById(data.getAlbumId()).ifPresent(album -> {
            String artistName = album.getPersonEntity().getName();
            String albumName = album.getName();

            if (imageRepository.findByAlbumEntityId(album.getId()).isEmpty()) {
                musicBrainzService.getCoverArtUrl(artistName, albumName).ifPresentOrElse(
                        url -> {
                            try {
                                imageDownloadService.downloadAndSave(url, ImageType.COVER, "eng",
                                        "MusicBrainz://" + url, new ImageSave.MediaEntityRef(null, null, null, null, album));
                            } catch (IOException e) {
                                log.warn("Failed to download cover art for album={}: {}", albumName, e.getMessage());
                            }
                        },
                        () -> log.debug("No cover art found on MusicBrainz for artist={} album={}", artistName, albumName)
                );
            }

            List<MetadataEntity> existingMetadata = metadataRepository.findByAlbumEntityId(album.getId());
            boolean hasDescription = existingMetadata.stream().anyMatch(m -> m.getDescription() != null && !m.getDescription().isBlank());
            if (!hasDescription) {
                musicBrainzService.getAlbumInfo(artistName, albumName).ifPresent(info -> {
                    if (existingMetadata.isEmpty()) {
                        metadataRepository.save(MetadataEntity.builder()
                                .description(info.description())
                                .albumEntity(album)
                                .sourceUri("musicbrainz://album/" + albumName)
                                .build());
                    } else {
                        // Rebuild preserving existing fields, adding description
                        MetadataEntity existing = existingMetadata.getFirst();
                        metadataRepository.deleteAll(existingMetadata);
                        metadataRepository.save(MetadataEntity.builder()
                                .title(existing.getTitle())
                                .description(info.description())
                                .released(existing.getReleased())
                                .genre(existing.getGenre())
                                .language(existing.getLanguage())
                                .albumEntity(album)
                                .sourceUri(existing.getSourceUri())
                                .build());
                    }
                });
            }
        });
    }
}
