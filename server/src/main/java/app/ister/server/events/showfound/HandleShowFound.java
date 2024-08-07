package app.ister.server.events.showfound;

import app.ister.server.entitiy.NodeEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.enums.DirectoryType;
import app.ister.server.enums.EventType;
import app.ister.server.enums.ImageType;
import app.ister.server.events.Handle;
import app.ister.server.events.TMDBMetadata.ImageDownload;
import app.ister.server.events.TMDBMetadata.ImageSave;
import app.ister.server.events.TMDBMetadata.MetadataSave;
import app.ister.server.events.TMDBMetadata.ShowMetadata;
import app.ister.server.events.TMDBMetadata.TMDBResult;
import app.ister.server.repository.DirectoryRepository;
import app.ister.server.repository.ShowRepository;
import app.ister.server.service.NodeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import info.movito.themoviedbapi.tools.TmdbException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.ister.server.events.MessageQueue.APP_ISTER_SERVER_SHOW_FOUND;

@Service
@Transactional
@Slf4j
public class HandleShowFound implements Handle<ShowFoundData> {
    // List of languages in https://en.wikipedia.org/wiki/ISO_639-1.
    private static final List<String> supportLanguages = List.of("en", "nl");
    @Autowired
    private NodeService nodeService;
    @Autowired
    private DirectoryRepository directoryRepository;
    @Autowired
    private ShowRepository showRepository;
    @Autowired
    private ShowMetadata showMetadata;
    @Autowired
    private MetadataSave metaDataSave;
    @Autowired
    private ImageDownload imageDownload;
    @Autowired
    private ImageSave imageSave;
    @Value("${app.ister.server.TMDB.apikey:'No api key available'}")
    private String apikey;

    @Override
    public EventType handles() {
        return EventType.SHOW_FOUND;
    }

    @RabbitListener(queues = APP_ISTER_SERVER_SHOW_FOUND)
    @Override
    public void listener(ShowFoundData showFoundData) {
        Handle.super.listener(showFoundData);
    }

    @Override
    public Boolean handle(ShowFoundData showFoundData) {
        // If no tmdb api key is set. Skip this event.
        if (apikey.equals("'No api key available'")) {
            return true;
        }
        try {
            ShowEntity showEntity = showRepository.findById(showFoundData.getShowId()).orElseThrow();
            for (String language : supportLanguages) {
                Optional<TMDBResult> TMDBResult = showMetadata.getMetadata(showEntity.getName(), showEntity.getReleaseYear(), language);
                if (TMDBResult.isPresent()) {
                    metaDataSave.save(TMDBResult.get(), null, showEntity, null);
                    if (TMDBResult.get().getBackgroundUrl() != null) {
                        getAndSaveImage(TMDBResult.get().getBackgroundUrl(), ImageType.BACKGROUND, TMDBResult.get().getLanguage(), showEntity);
                    }
                    if (TMDBResult.get().getPosterUrl() != null) {
                        getAndSaveImage(TMDBResult.get().getPosterUrl(), ImageType.COVER, TMDBResult.get().getLanguage(), showEntity);
                    }
                }
            }
        } catch (JsonProcessingException jpe) {
            log.error("Cannot convert JSON into ShowFoundData", jpe);
            return false;
        } catch (TmdbException e) {
            log.error("Cannot get TMDB data", e);
            return false;
        } catch (IOException e) {
            log.error("Download and saving image failed", e);
            return false;
        }
        return true;
    }

    private void getAndSaveImage(String imageUrl, ImageType imageType, String language, ShowEntity showEntity) throws IOException {
        NodeEntity nodeEntity = nodeService.getOrCreateNodeEntityForThisNode();
        var cacheDisk = directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity).stream().findFirst().orElseThrow();

        String toPath = cacheDisk.getPath() + UUID.randomUUID() + ".jpg";
        imageDownload.download(imageUrl, toPath);
        imageSave.save(cacheDisk, toPath, imageType, language, "TMDB://" + imageUrl, null, showEntity, null);
    }
}
