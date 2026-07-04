package app.ister.worker.events.episodefound;

import app.ister.core.MessageQueue;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.EpisodeFoundData;
import app.ister.core.repository.EpisodeRepository;
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
    private final CreditsService creditsService;

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
    public void handle(app.ister.core.eventdata.EpisodeFoundData episodeFoundData) {
        // If no tmdb api key is set. Skip this event.
        if (apikey == null || apikey.isBlank()) {
            log.warn("No TMDB API key configured, skipping metadata fetch");
            return;
        }
        try {
            var episodeEntity = episodeRepository.findById(episodeFoundData.getEpisodeId()).orElseThrow();
            Integer tmdbSeriesId = null;
            for (String language : supportLanguages) {
                var showEntity = episodeEntity.getShowEntity();
                Optional<TMDBResult> tmdbResult = episodeMetadata.getMetadata(showEntity.getName(), showEntity.getReleaseYear(), episodeEntity.getSeasonEntity().getNumber(), episodeEntity.getNumber(), language);
                if (tmdbResult.isPresent()) {
                    metaDataSave.save(tmdbResult.get(), null, null, episodeEntity);
                    if (tmdbResult.get().getBackgroundUrl() != null) {
                        imageDownloadService.downloadAndSave(tmdbResult.get().getBackgroundUrl(), ImageType.BACKGROUND, tmdbResult.get().getLanguage(), "TMDB://" + tmdbResult.get().getBackgroundUrl(), new ImageSave.MediaEntityRef(null, null, episodeEntity, null, null));
                    }
                    if (tmdbResult.get().getPosterUrl() != null) {
                        imageDownloadService.downloadAndSave(tmdbResult.get().getPosterUrl(), ImageType.COVER, tmdbResult.get().getLanguage(), "TMDB://" + tmdbResult.get().getPosterUrl(), new ImageSave.MediaEntityRef(null, null, episodeEntity, null, null));
                    }
                    if (tmdbSeriesId == null) {
                        tmdbSeriesId = tmdbResult.get().getSeriesTmdbId();
                    }
                }
            }
            // Credits are language independent: fetch once, incl. guest stars for this episode.
            if (tmdbSeriesId != null) {
                creditsService.fetchForEpisode(episodeEntity, tmdbSeriesId,
                        episodeEntity.getSeasonEntity().getNumber(), episodeEntity.getNumber());
            }
        } catch (IOException e) {
            throw new EventHandlingException("Download and saving image failed", e);
        }
    }
}
