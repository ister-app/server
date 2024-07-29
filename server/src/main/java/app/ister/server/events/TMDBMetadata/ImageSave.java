package app.ister.server.events.TMDBMetadata;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.ImageEntity;
import app.ister.server.entitiy.MovieEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.enums.ImageType;
import app.ister.server.repository.ImageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
public class ImageSave {
    @Autowired
    private ImageRepository imageRepository;

    public void save(DirectoryEntity cacheDisk, String toPath, ImageType imageType, String language, String sourceUri, @Nullable MovieEntity movieEntity, @Nullable ShowEntity showEntity, @Nullable EpisodeEntity episodeEntity) {
        imageRepository.save(ImageEntity.builder()
                .directoryEntity(cacheDisk)
                .path(toPath)
                .type(imageType)
                .language(language)
                .sourceUri(sourceUri)
                .movieEntity(movieEntity)
                .showEntity(showEntity)
                .episodeEntity(episodeEntity)
                .build());
    }
}
