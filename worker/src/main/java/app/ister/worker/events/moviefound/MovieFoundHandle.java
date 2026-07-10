package app.ister.worker.events.moviefound;

import app.ister.core.config.LanguageProperties;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.MovieFoundData;
import app.ister.core.repository.MovieRepository;
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

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_MOVIE_FOUND;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class MovieFoundHandle implements Handle<MovieFoundData> {
    private final MovieRepository movieRepository;
    private final MovieMetadata movieMetadata;
    private final MetadataSave metaDataSave;
    private final ImageDownloadService imageDownloadService;
    private final CreditsService creditsService;
    private final LanguageProperties languageProperties;

    @Value("${app.ister.server.TMDB.apikey:}")
    private String apikey;

    @RabbitListener(queues = APP_ISTER_SERVER_MOVIE_FOUND)
    @Override
    public void listener(app.ister.core.eventdata.MovieFoundData messageData) {
        Handle.super.listener(messageData);
    }

    @Override
    public EventType handles() {
        return EventType.MOVIE_FOUND;
    }

    @Override
    public void handle(app.ister.core.eventdata.MovieFoundData movieFoundData) {
        // If no tmdb api key is set. Skip this event.
        if (apikey == null || apikey.isBlank()) {
            log.warn("No TMDB API key configured, skipping metadata fetch");
            return;
        }
        try {
            var movieEntity = movieRepository.findById(movieFoundData.getMovieId()).orElseThrow();
            Integer tmdbMovieId = null;
            for (String language : languageProperties.tags()) {
                Optional<TMDBResult> tmdbResult = movieMetadata.getMetadata(movieEntity.getName(), movieEntity.getReleaseYear(), language);
                if (tmdbResult.isPresent()) {
                    metaDataSave.save(tmdbResult.get(), movieEntity, null, null);
                    saveImages(tmdbResult.get(), movieEntity);
                    if (tmdbMovieId == null) {
                        tmdbMovieId = tmdbResult.get().getTmdbId();
                    }
                }
            }
            // Credits are language independent: fetch once.
            if (tmdbMovieId != null) {
                creditsService.fetchForMovie(movieEntity, tmdbMovieId);
            }
        } catch (IOException e) {
            throw new EventHandlingException("Download and saving image failed", e);
        }
    }

    /** Downloads and saves the background and poster of one language-specific TMDB result. */
    private void saveImages(TMDBResult tmdbResult, app.ister.core.entity.MovieEntity movieEntity) throws IOException {
        if (tmdbResult.getBackgroundUrl() != null) {
            imageDownloadService.downloadAndSave(tmdbResult.getBackgroundUrl(), ImageType.BACKGROUND, tmdbResult.getLanguage(), "TMDB://" + tmdbResult.getBackgroundUrl(), new ImageSave.MediaEntityRef(movieEntity, null, null, null, null));
        }
        if (tmdbResult.getPosterUrl() != null) {
            imageDownloadService.downloadAndSave(tmdbResult.getPosterUrl(), ImageType.COVER, tmdbResult.getLanguage(), "TMDB://" + tmdbResult.getPosterUrl(), new ImageSave.MediaEntityRef(movieEntity, null, null, null, null));
        }
    }
}
