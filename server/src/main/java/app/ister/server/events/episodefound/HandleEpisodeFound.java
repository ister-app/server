package app.ister.server.events.episodefound;

import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.NodeEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.enums.DirectoryType;
import app.ister.server.enums.EventType;
import app.ister.server.enums.ImageType;
import app.ister.server.events.Handle;
import app.ister.server.events.MessageQueue;
import app.ister.server.events.TMDBMetadata.*;
import app.ister.server.repository.DirectoryRepository;
import app.ister.server.repository.EpisodeRepository;
import app.ister.server.service.NodeService;
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

@Service
@Slf4j
@Transactional
public class HandleEpisodeFound implements Handle<EpisodeFoundData> {
    // List of languages in https://en.wikipedia.org/wiki/ISO_639-1.
    private static final List<String> supportLanguages = List.of("en", "nl");
    @Autowired
    private NodeService nodeService;
    @Autowired
    private DirectoryRepository directoryRepository;
    @Autowired
    private EpisodeRepository episodeRepository;
    @Autowired
    private EpisodeMetadata episodeMetadata;
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
        return EventType.EPISODE_FOUND;
    }

    @RabbitListener(queues = MessageQueue.APP_ISTER_SERVER_EPISODE_FOUND)
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
                    metaDataSave.save(TMDBResult.get(), null, null, episodeEntity);
                    if (TMDBResult.get().getBackgroundUrl() != null) {
                        getAndSaveImage(TMDBResult.get().getBackgroundUrl(), ImageType.BACKGROUND, TMDBResult.get().getLanguage(), episodeEntity);
                    }
                    if (TMDBResult.get().getPosterUrl() != null) {
                        getAndSaveImage(TMDBResult.get().getPosterUrl(), ImageType.COVER, TMDBResult.get().getLanguage(), episodeEntity);
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

    private void getAndSaveImage(String imageUrl, ImageType imageType, String language, EpisodeEntity episodeEntity) throws IOException {
        NodeEntity nodeEntity = nodeService.getOrCreateNodeEntityForThisNode();
        var cacheDisk = directoryRepository.findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity).stream().findFirst().orElseThrow();

        String toPath = cacheDisk.getPath() + UUID.randomUUID() + ".jpg";
        imageDownload.download(imageUrl, toPath);
        imageSave.save(cacheDisk, toPath, imageType, language, "TMDB://" + imageUrl, null, null, episodeEntity);
    }
}
