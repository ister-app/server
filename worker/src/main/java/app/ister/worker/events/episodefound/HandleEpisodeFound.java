package app.ister.worker.events.episodefound;

import app.ister.core.MessageQueue;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.EpisodeFoundData;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.Handle;
import app.ister.worker.events.TMDBMetadata.*;
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

@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class HandleEpisodeFound implements Handle<EpisodeFoundData> {
    // List of languages in https://en.wikipedia.org/wiki/ISO_639-1.
    private static final List<String> supportLanguages = List.of("en", "nl");

    private final EpisodeRepository episodeRepository;
    private final EpisodeMetadata episodeMetadata;
    private final MetadataSave metaDataSave;
    private final ImageDownloadService imageDownloadService;

    @Value("${app.ister.server.TMDB.apikey:}")
    private String apikey;

    @Override
    public EventType handles() {
        return EventType.EPISODE_FOUND;
    }

    @RabbitListener(queues = MessageQueue.APP_ISTER_SERVER_EPISODE_FOUND)
    @Override
    public void listener(app.ister.core.eventdata.EpisodeFoundData episodeFoundData) {
        Handle.super.listener(episodeFoundData);
    }

    @Override
    public Boolean handle(app.ister.core.eventdata.EpisodeFoundData episodeFoundData) {
        // If no tmdb api key is set. Skip this event.
        if (apikey == null || apikey.isBlank()) {
            log.warn("No TMDB API key configured, skipping metadata fetch");
            return true;
        }
        try {
            var episodeEntity = episodeRepository.findById(episodeFoundData.getEpisodeId()).orElseThrow();
            for (String language : supportLanguages) {
                var showEntity = episodeEntity.getShowEntity();
                Optional<TMDBResult> TMDBResult = episodeMetadata.getMetadata(showEntity.getName(), showEntity.getReleaseYear(), episodeEntity.getSeasonEntity().getNumber(), episodeEntity.getNumber(), language);
                if (TMDBResult.isPresent()) {
                    metaDataSave.save(TMDBResult.get(), null, null, episodeEntity);
                    if (TMDBResult.get().getBackgroundUrl() != null) {
                        imageDownloadService.downloadAndSave(TMDBResult.get().getBackgroundUrl(), ImageType.BACKGROUND, TMDBResult.get().getLanguage(), null, null, episodeEntity);
                    }
                    if (TMDBResult.get().getPosterUrl() != null) {
                        imageDownloadService.downloadAndSave(TMDBResult.get().getPosterUrl(), ImageType.COVER, TMDBResult.get().getLanguage(), null, null, episodeEntity);
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
