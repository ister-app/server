package app.ister.worker.events.moviefound;

import app.ister.core.entitiy.MovieEntity;
import app.ister.core.entitiy.NodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.MovieFoundData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.service.NodeService;
import app.ister.worker.events.Handle;
import app.ister.worker.events.TMDBMetadata.*;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_MOVIE_FOUND;

@Service
@Transactional
@Slf4j
public class MovieFoundHandle implements Handle<MovieFoundData> {
    // List of languages in https://en.wikipedia.org/wiki/ISO_639-1.
    private static final List<String> supportLanguages = List.of("en", "nl");
    @Autowired
    private NodeService nodeService;
    @Autowired
    private DirectoryRepository directoryRepository;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private MovieMetadata movieMetadata;
    @Autowired
    private MetadataSave metaDataSave;
    @Autowired
    private ImageDownload imageDownload;
    @Autowired
    private ImageSave imageSave;
    @Value("${app.ister.server.TMDB.apikey:'No api key available'}")
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
        if (apikey.equals("'No api key available'")) {
            return true;
        }
        try {
            MovieEntity movieEntity = movieRepository.findById(movieFoundData.getMovieId()).orElseThrow();
            for (String language : supportLanguages) {
                Optional<TMDBResult> TMDBResult = movieMetadata.getMetadata(movieEntity.getName(), movieEntity.getReleaseYear(), language);
                if (TMDBResult.isPresent()) {
                    metaDataSave.save(TMDBResult.get(), movieEntity, null, null);
                    if (TMDBResult.get().getBackgroundUrl() != null) {
                        getAndSaveImage(TMDBResult.get().getBackgroundUrl(), ImageType.BACKGROUND, TMDBResult.get().getLanguage(), movieEntity);
                    }
                    if (TMDBResult.get().getPosterUrl() != null) {
                        getAndSaveImage(TMDBResult.get().getPosterUrl(), ImageType.COVER, TMDBResult.get().getLanguage(), movieEntity);
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

    private void getAndSaveImage(String imageUrl, ImageType imageType, String language, MovieEntity movieEntity) throws IOException {
        NodeEntity nodeEntity = nodeService.getOrCreateNodeEntityForThisNode();
        var cacheDisk = directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity).stream().findFirst().orElseThrow();

        String toPath = cacheDisk.getPath() + UUID.randomUUID() + ".jpg";
        imageDownload.download(imageUrl, toPath);
        imageSave.save(cacheDisk, toPath, imageType, language, "TMDB://" + imageUrl, movieEntity, null, null);
    }
}
