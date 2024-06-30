package app.ister.server.eventHandlers;

import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.NodeEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.enums.DirectoryType;
import app.ister.server.enums.EventType;
import app.ister.server.enums.ImageType;
import app.ister.server.eventHandlers.TMDBMetadata.EpisodeMetadata;
import app.ister.server.eventHandlers.TMDBMetadata.ImageDownload;
import app.ister.server.eventHandlers.TMDBMetadata.ImageSave;
import app.ister.server.eventHandlers.TMDBMetadata.TMDBResult;
import app.ister.server.eventHandlers.TMDBMetadata.metadataSave;
import app.ister.server.eventHandlers.data.EpisodeFoundData;
import app.ister.server.repository.DirectoryRepository;
import app.ister.server.repository.EpisodeRepository;
import app.ister.server.service.NodeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.movito.themoviedbapi.model.core.responses.TmdbResponseException;
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

import static app.ister.server.eventHandlers.MessageQueue.APP_ISTER_SERVER_EPISODE_FOUND;

@Service
@Slf4j
@Transactional
public class HandleEpisodeFound implements Handle<EpisodeFoundData> {
    @Autowired
    private NodeService nodeService;
    @Autowired
    private DirectoryRepository directoryRepository;
    @Autowired
    private EpisodeRepository episodeRepository;
    @Autowired
    private EpisodeMetadata episodeMetadata;
    @Autowired
    private metadataSave metaDataSave;
    @Autowired
    private ImageDownload imageDownload;
    @Autowired
    private ImageSave imageSave;

    @Value("${app.ister.server.TMDB.apikey:'No api key available'}")
    private String apikey;

    // List of languages in https://en.wikipedia.org/wiki/ISO_639-1.
    private static final List<String> supportLanguages = List.of("en", "nl");

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public EventType handles() {
        return EventType.EPISODE_FOUND;
    }

    @RabbitListener(queues = APP_ISTER_SERVER_EPISODE_FOUND)
    @Override
    public void listener(EpisodeFoundData episodeFoundData) {
        Handle.super.listener(episodeFoundData);
    }

    @Override
    public Boolean handle(EpisodeFoundData episodeFoundData) {
        // If no tmdb api key is set. Skip this event.
        if (apikey.equals("'No api key available'")) {
            return true;
        }
        try {
            EpisodeEntity episodeEntity = episodeRepository.findById(episodeFoundData.getEpisodeId()).orElseThrow();
            for (String language : supportLanguages) {
                ShowEntity showEntity = episodeEntity.getShowEntity();
                Optional<TMDBResult> TMDBResult = episodeMetadata.getMetadata(showEntity.getName(), showEntity.getReleaseYear(), episodeEntity.getSeasonEntity().getNumber(), episodeEntity.getNumber(), language);
                if (TMDBResult.isPresent()) {
                    metaDataSave.save(TMDBResult.get(), null, episodeEntity);
                    if (TMDBResult.get().getBackgroundUrl() != null) {
                        getAndSaveImage(TMDBResult.get().getBackgroundUrl(), ImageType.BACKGROUND, TMDBResult.get().getLanguage(), episodeEntity);
                    }
                    if (TMDBResult.get().getPosterUrl() != null) {
                        getAndSaveImage(TMDBResult.get().getPosterUrl(), ImageType.COVER, TMDBResult.get().getLanguage(), episodeEntity);
                    }
                }
            }
        } catch (JsonProcessingException jpe) {
            log.error("Cannot convert JSON into ShowFoundData", jpe);
            return false;
        } catch (TmdbResponseException e) {
            log.error("Cannot get TMDB data response", e);
        } catch (TmdbException e) {
            log.error("Cannot get TMDB data", e);
            return false;
        } catch (IOException e) {
            log.error("Download and saving image failed", e);
            return false;
        }
        return true;
    }

    private void getAndSaveImage(String imageUrl, ImageType imageType, String language, EpisodeEntity episodeEntity) throws IOException {
        NodeEntity nodeEntity = nodeService.getOrCreateNodeEntityForThisNode();
        var cacheDisk = directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity).stream().findFirst().orElseThrow();

        String toPath = cacheDisk.getPath() + UUID.randomUUID() + ".jpg";
        imageDownload.download(imageUrl, toPath);
        imageSave.save(cacheDisk, toPath, imageType, language, "TMDB://" + imageUrl, null, episodeEntity);
    }
}
