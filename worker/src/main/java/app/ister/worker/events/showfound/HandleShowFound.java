package app.ister.worker.events.showfound;

import app.ister.core.config.LanguageProperties;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.repository.ShowRepository;
import app.ister.core.EventHandlingException;
import app.ister.core.Handle;
import app.ister.worker.events.tmdbmetadata.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Optional;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_SHOW_FOUND;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class HandleShowFound implements Handle<app.ister.core.eventdata.ShowFoundData> {
    private final ShowRepository showRepository;
    private final ShowMetadata showMetadata;
    private final MetadataSave metaDataSave;
    private final ImageDownloadService imageDownloadService;
    private final CreditsService creditsService;
    private final LanguageProperties languageProperties;

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
    public void handle(app.ister.core.eventdata.ShowFoundData showFoundData) {
        // If no tmdb api key is set. Skip this event.
        if (apikey == null || apikey.isBlank()) {
            log.warn("No TMDB API key configured, skipping metadata fetch");
            return;
        }
        try {
            var showEntity = showRepository.findById(showFoundData.getShowId()).orElseThrow();
            Integer tmdbSeriesId = null;
            for (String language : languageProperties.tags()) {
                Optional<TMDBResult> tmdbResult = showMetadata.getMetadata(showEntity.getName(), showEntity.getReleaseYear(), language);
                if (tmdbResult.isPresent()) {
                    metaDataSave.save(tmdbResult.get(), null, showEntity, null);
                    saveImages(tmdbResult.get(), showEntity);
                    if (tmdbSeriesId == null) {
                        tmdbSeriesId = tmdbResult.get().getTmdbId();
                    }
                }
            }
            // Credits are language independent: fetch once.
            if (tmdbSeriesId != null) {
                creditsService.fetchForShow(showEntity, tmdbSeriesId);
            }
        } catch (IOException e) {
            throw new EventHandlingException("Download and saving image failed", e);
        }
    }

    /** Downloads and saves the background and poster of one language-specific TMDB result. */
    private void saveImages(TMDBResult tmdbResult, app.ister.core.entity.ShowEntity showEntity) throws IOException {
        if (tmdbResult.getBackgroundUrl() != null) {
            imageDownloadService.downloadAndSave(tmdbResult.getBackgroundUrl(), ImageType.BACKGROUND, tmdbResult.getLanguage(), "TMDB://" + tmdbResult.getBackgroundUrl(), new ImageSave.MediaEntityRef(null, showEntity, null, null, null));
        }
        if (tmdbResult.getPosterUrl() != null) {
            imageDownloadService.downloadAndSave(tmdbResult.getPosterUrl(), ImageType.COVER, tmdbResult.getLanguage(), "TMDB://" + tmdbResult.getPosterUrl(), new ImageSave.MediaEntityRef(null, showEntity, null, null, null));
        }
    }
}
