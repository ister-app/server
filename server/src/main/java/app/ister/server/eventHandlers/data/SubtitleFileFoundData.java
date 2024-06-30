package app.ister.server.eventHandlers.data;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class SubtitleFileFoundData extends MessageData {
    private UUID directoryEntityUUID;
    private String path;
}
