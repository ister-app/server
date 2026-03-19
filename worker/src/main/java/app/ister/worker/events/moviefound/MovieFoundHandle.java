package app.ister.worker.events.moviefound;

import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.MovieFoundData;
import app.ister.core.repository.MovieRepository;
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

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_MOVIE_FOUND;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class MovieFoundHandle implements Handle<MovieFoundData> {
    // List of languages in https://en.wikipedia.org/wiki/ISO_639-1.
    private static final List<String> supportLanguages = List.of("en", "nl");

    private final MovieRepository movieRepository;
    private final MovieMetadata movieMetadata;
    private final MetadataSave metaDataSave;
    private final ImageDownloadService imageDownloadService;

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
    public Boolean handle(app.ister.core.eventdata.MovieFoundData movieFoundData) {
        // If no tmdb api key is set. Skip this event.
        if (apikey == null || apikey.isBlank()) {
            log.warn("No TMDB API key configured, skipping metadata fetch");
            return true;
        }
        try {
            var movieEntity = movieRepository.findById(movieFoundData.getMovieId()).orElseThrow();
            for (String language : supportLanguages) {
                Optional<TMDBResult> TMDBResult = movieMetadata.getMetadata(movieEntity.getName(), movieEntity.getReleaseYear(), language);
                if (TMDBResult.isPresent()) {
                    metaDataSave.save(TMDBResult.get(), movieEntity, null, null);
                    if (TMDBResult.get().getBackgroundUrl() != null) {
                        imageDownloadService.downloadAndSave(TMDBResult.get().getBackgroundUrl(), ImageType.BACKGROUND, TMDBResult.get().getLanguage(), movieEntity, null, null);
                    }
                    if (TMDBResult.get().getPosterUrl() != null) {
                        imageDownloadService.downloadAndSave(TMDBResult.get().getPosterUrl(), ImageType.COVER, TMDBResult.get().getLanguage(), movieEntity, null, null);
                    }
                }
            }
        } catch (JacksonException jpe) {
            log.error("Cannot convert JSON into MovieFoundData", jpe);
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
