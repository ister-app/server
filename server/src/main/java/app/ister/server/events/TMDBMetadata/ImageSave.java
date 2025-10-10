package app.ister.server.events.TMDBMetadata;

import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.MovieEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.enums.EventType;
import app.ister.server.enums.ImageType;
import app.ister.server.events.imagefound.ImageFoundData;
import app.ister.server.service.MessageSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
public class ImageSave {
    @Autowired
    private MessageSender messageSender;

    public void save(DirectoryEntity cacheDisk,
                     String path,
                     ImageType imageType,
                     String language,
                     String sourceUri,
                     @Nullable MovieEntity movieEntity,
                     @Nullable ShowEntity showEntity,
                     @Nullable EpisodeEntity episodeEntity) {
        messageSender.sendImageFound(ImageFoundData.builder()
                .eventType(EventType.IMAGE_FOUND)
                .directoryEntityId(cacheDisk.getId())
                .path(path)
                .imageType(imageType)
                .language(language)
                .sourceUri(sourceUri)
                .movieEntityId(movieEntity == null ? null : movieEntity.getId())
                .showEntityId(showEntity == null ? null : showEntity.getId())
                .episodeEntityId(episodeEntity == null ? null : episodeEntity.getId())
                .build());
    }
}
