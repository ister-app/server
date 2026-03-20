package app.ister.worker.events.showfound;

import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.repository.ShowRepository;
import app.ister.core.Handle;
import app.ister.worker.events.tmdbmetadata.*;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_SHOW_FOUND;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class HandleShowFound implements Handle<app.ister.core.eventdata.ShowFoundData> {
    // List of languages in https://en.wikipedia.org/wiki/ISO_639-1.
    private static final List<String> supportLanguages = List.of("en", "nl");

    private final ShowRepository showRepository;
    private final ShowMetadata showMetadata;
    private final MetadataSave metaDataSave;
    private final ImageDownloadService imageDownloadService;

    @Value("${app.ister.server.TMDB.apikey:}")
    private String apikey;

    @Override
    public EventType handles() {
        return EventType.SHOW_FOUND;
    }

    @RabbitListener(queues = APP_ISTER_SERVER_SHOW_FOUND)
    @Override
    public void listener(app.ister.core.eventdata.ShowFoundData showFoundData) {
        Handle.super.listener(showFoundData);
    }

    @Override
    public Boolean handle(app.ister.core.eventdata.ShowFoundData showFoundData) {
        // If no tmdb api key is set. Skip this event.
        if (apikey == null || apikey.isBlank()) {
            log.warn("No TMDB API key configured, skipping metadata fetch");
            return true;
        }
        try {
            var showEntity = showRepository.findById(showFoundData.getShowId()).orElseThrow();
            for (String language : supportLanguages) {
                Optional<TMDBResult> tmdbResult = showMetadata.getMetadata(showEntity.getName(), showEntity.getReleaseYear(), language);
                if (tmdbResult.isPresent()) {
                    metaDataSave.save(tmdbResult.get(), null, showEntity, null);
                    if (tmdbResult.get().getBackgroundUrl() != null) {
                        imageDownloadService.downloadAndSave(tmdbResult.get().getBackgroundUrl(), ImageType.BACKGROUND, tmdbResult.get().getLanguage(), null, showEntity, null);
                    }
                    if (tmdbResult.get().getPosterUrl() != null) {
                        imageDownloadService.downloadAndSave(tmdbResult.get().getPosterUrl(), ImageType.COVER, tmdbResult.get().getLanguage(), null, showEntity, null);
                    }
                }
            }
        } catch (JacksonException jpe) {
            log.error("Cannot convert JSON into ShowFoundData", jpe);
            return false;
        } catch (FeignException e) {
            log.error("Cannot get TMDB data", e);
            return false;
        } catch (IOException e) {
            log.error("Download and saving image failed", e);
            return false;
        }
        return true;
    }
}
