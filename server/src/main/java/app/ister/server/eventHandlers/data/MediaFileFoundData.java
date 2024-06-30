package app.ister.server.eventHandlers.data;

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
public class MediaFileFoundData extends MessageData {
    private UUID directoryEntityUUID;
    private UUID episodeEntityUUID;
    private String path;
}
