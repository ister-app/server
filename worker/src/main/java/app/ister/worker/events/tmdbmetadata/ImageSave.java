package app.ister.worker.events.tmdbmetadata;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.service.MessageSender;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImageSave {
    private final MessageSender messageSender;

    public record MediaEntityRef(@Nullable MovieEntity movie,
                                  @Nullable ShowEntity show,
                                  @Nullable EpisodeEntity episode) {}

    public void save(DirectoryEntity cacheDisk,
                     String path,
                     ImageType imageType,
                     String language,
                     String sourceUri,
                     MediaEntityRef mediaEntityRef) {
        messageSender.sendImageFound(ImageFoundData.builder()
                .eventType(EventType.IMAGE_FOUND)
                .directoryEntityId(cacheDisk.getId())
                .path(path)
                .imageType(imageType)
                .language(language)
                .sourceUri(sourceUri)
                .movieEntityId(mediaEntityRef.movie() == null ? null : mediaEntityRef.movie().getId())
                .showEntityId(mediaEntityRef.show() == null ? null : mediaEntityRef.show().getId())
                .episodeEntityId(mediaEntityRef.episode() == null ? null : mediaEntityRef.episode().getId())
                .build(), cacheDisk.getNodeEntity().getName());
    }
}
