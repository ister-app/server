package app.ister.worker.events.TMDBMetadata;

import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.entitiy.EpisodeEntity;
import app.ister.core.entitiy.MovieEntity;
import app.ister.core.entitiy.ShowEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.service.MessageSender;
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
