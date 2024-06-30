package app.ister.server.events.subtitlefilefound;

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
public class SubtitleFileFoundData extends MessageData {
    private UUID directoryEntityUUID;
    private String path;
}
