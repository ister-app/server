package app.ister.core.eventdata;

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
public class AnalyzeData extends MessageData {
    private UUID episodeId;
    private UUID movieId;
    private UUID showId;
    private UUID libraryId;
    private UUID directoryId;
    private UUID artistId;
    private UUID albumId;
    private UUID trackId;
}
