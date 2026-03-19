package app.ister.core.eventdata;

import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
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
                .movieEntityId(imageEntity.getMovieEntity() == null ? imageEntity.getMovieEntityId() : imageEntity.getMovieEntity().getId())
                .showEntityId(imageEntity.getShowEntity() == null ? imageEntity.getShowEntityId() : imageEntity.getShowEntity().getId())
                .episodeEntityId(imageEntity.getEpisodeEntity() == null ? imageEntity.getEpisodeEntityId() : imageEntity.getEpisodeEntity().getId())
                .build();
    }
}
