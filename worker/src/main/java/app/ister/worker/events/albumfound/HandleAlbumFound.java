package app.ister.worker.events.albumfound;

import app.ister.core.Handle;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.AlbumFoundData;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.worker.events.musicbrainz.MusicBrainzService;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_ALBUM_FOUND;

@Slf4j
@Service("workerHandleAlbumFound")
@Transactional
@RequiredArgsConstructor
public class HandleAlbumFound implements Handle<AlbumFoundData> {

    private final AlbumRepository albumRepository;
    private final ImageRepository imageRepository;
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
    public Boolean handle(AlbumFoundData data) {
        albumRepository.findById(data.getAlbumId()).ifPresent(album -> {
            if (!imageRepository.findByAlbumEntityId(album.getId()).isEmpty()) {
                return;
            }
            String artistName = album.getArtistEntity().getName();
            String albumName = album.getName();
            musicBrainzService.getCoverArtUrl(artistName, albumName).ifPresentOrElse(
                    url -> {
                        try {
                            imageDownloadService.downloadAndSave(url, ImageType.COVER, "eng",
                                    "MusicBrainz://" + url, null, null, null, null, album);
                        } catch (IOException e) {
                            log.warn("Failed to download cover art for album={}: {}", albumName, e.getMessage());
                        }
                    },
                    () -> log.debug("No cover art found on MusicBrainz for artist={} album={}", artistName, albumName)
            );
        });
        return true;
    }
}
