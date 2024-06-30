package app.ister.server.events.mediafilefound;

import app.ister.server.events.MessageData;
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
