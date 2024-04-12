package app.ister.server.eventHandlers;

import app.ister.server.entitiy.NodeEntity;
import app.ister.server.entitiy.ServerEventEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.enums.DirectoryType;
import app.ister.server.enums.ImageType;
import app.ister.server.eventHandlers.TMDBMetadata.ImageDownload;
import app.ister.server.eventHandlers.TMDBMetadata.ImageSave;
import app.ister.server.eventHandlers.TMDBMetadata.ShowMetadata;
import app.ister.server.eventHandlers.TMDBMetadata.TMDBResult;
import app.ister.server.eventHandlers.TMDBMetadata.metadataSave;
import app.ister.server.repository.DirectoryRepository;
import app.ister.server.repository.ShowRepository;
import app.ister.server.service.NodeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.movito.themoviedbapi.tools.TmdbException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
public class HandleShowFound implements Handle {
    @Autowired
    private NodeService nodeService;
    @Autowired
    private DirectoryRepository directoryRepository;
    @Autowired
    private ShowRepository showRepository;
    @Autowired
    private ShowMetadata showMetadata;
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
    public Boolean handle(ServerEventEntity serverEventEntity) {
        // If no tmdb api key is set. Skip this event.
        if (apikey.equals("'No api key available'")) {
            return true;
        }
        try {
            ShowFoundData showFoundData = objectMapper.readValue(serverEventEntity.getData(), new TypeReference<>() {});
            ShowEntity showEntity = showRepository.findById(showFoundData.getShowId()).orElseThrow();
            for (String language : supportLanguages) {
                Optional<TMDBResult> TMDBResult = showMetadata.getMetadata(showEntity.getName(), showEntity.getReleaseYear(), language);
                if (TMDBResult.isPresent()) {
                    metaDataSave.save(TMDBResult.get(), showEntity, null);
                    if (TMDBResult.get().getBackgroundUrl() != null) {
                        getAndSaveImage(TMDBResult.get().getBackgroundUrl(), ImageType.BACKGROUND, TMDBResult.get().getLanguage(), showEntity);
                    }
                    if (TMDBResult.get().getPosterUrl() != null) {
                        getAndSaveImage(TMDBResult.get().getPosterUrl(), ImageType.COVER, TMDBResult.get().getLanguage(), showEntity);
                    }
                };
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
        imageSave.save(cacheDisk, toPath, imageType, language, "TMDB://" + imageUrl, showEntity, null);
    }
}
