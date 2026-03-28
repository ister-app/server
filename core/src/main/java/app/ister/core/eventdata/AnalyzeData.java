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
    private UUID episodeId;    // nullable
    private UUID movieId;      // nullable
    private UUID showId;       // nullable — fan-out step for library re-analysis
    private UUID libraryId;    // nullable — triggers fan-out to all shows/movies
    private UUID directoryId;  // null when sent to worker; set by worker when fanning out to disk
}
