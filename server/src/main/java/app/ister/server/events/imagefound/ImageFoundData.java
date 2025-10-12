package app.ister.server.events.imagefound;

import app.ister.server.entitiy.ImageEntity;
import app.ister.server.enums.EventType;
import app.ister.server.enums.ImageType;
import app.ister.server.events.MessageData;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.lang.Nullable;

import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class ImageFoundData extends MessageData {
    private UUID directoryEntityId;
    private String path;
    private ImageType imageType;
    private String language;
    private String sourceUri;
    @Nullable
    private UUID movieEntityId;
    @Nullable
    private UUID showEntityId;
    @Nullable
    private UUID episodeEntityId;

    public static ImageFoundData fromImageEntity(ImageEntity imageEntity) {
        return ImageFoundData.builder()
                .eventType(EventType.IMAGE_FOUND)
                .directoryEntityId(imageEntity.getDirectoryEntity() == null ? imageEntity.getDirectoryEntityId() : imageEntity.getDirectoryEntity().getId())
                .path(imageEntity.getPath())
                .imageType(imageEntity.getType())
                .language(imageEntity.getLanguage())
                .sourceUri(imageEntity.getSourceUri())
                .movieEntityId(imageEntity.getMovieEntity() == null ? null : imageEntity.getMovieEntity().getId())
                .showEntityId(imageEntity.getShowEntity() == null ? null : imageEntity.getShowEntity().getId())
                .episodeEntityId(imageEntity.getEpisodeEntity() == null ? null : imageEntity.getEpisodeEntity().getId())
                .build();
    }
}
