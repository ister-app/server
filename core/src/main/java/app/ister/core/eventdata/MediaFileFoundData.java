package app.ister.core.eventdata;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class MediaFileFoundData extends MessageData {
    private UUID directoryEntityUUID;
    private UUID episodeEntityUUID;
    private UUID movieEntityUUID;
    /**
     * All episodes for a multi-episode file (s04e06-e07.mkv), in file order. Null or singleton for
     * normal files; episodeEntityUUID stays the first episode so in-flight messages keep working.
     */
    private List<UUID> episodeEntityUUIDs;
    private String path;
}
