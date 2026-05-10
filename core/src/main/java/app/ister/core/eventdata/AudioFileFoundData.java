package app.ister.core.eventdata;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.enums.EventType;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class AudioFileFoundData extends MessageData {
    private UUID directoryEntityUUID;
    private UUID trackEntityUUID;
    private String path;

    public static AudioFileFoundData fromMediaFileEntity(MediaFileEntity m) {
        return AudioFileFoundData.builder()
                .eventType(EventType.AUDIO_FILE_FOUND)
                .directoryEntityUUID(m.getDirectoryEntityId())
                .trackEntityUUID(m.getTrackEntity() != null ? m.getTrackEntity().getId() : null)
                .path(m.getPath())
                .build();
    }
}
