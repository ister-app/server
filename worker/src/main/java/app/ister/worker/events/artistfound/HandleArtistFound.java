package app.ister.worker.events.artistfound;

import app.ister.core.Handle;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.ArtistFoundData;
import app.ister.core.repository.ArtistRepository;
import app.ister.core.repository.ImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_ARTIST_FOUND;

@Slf4j
@Service("workerHandleArtistFound")
@Transactional
@RequiredArgsConstructor
public class HandleArtistFound implements Handle<ArtistFoundData> {

    private final ArtistRepository artistRepository;
    private final ImageRepository imageRepository;

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
            if (!imageRepository.findByArtistEntityId(artist.getId()).isEmpty()) {
                return;
            }
            log.debug("No artist image source available for artist={}, skipping", artist.getName());
        });
        return true;
    }
}
