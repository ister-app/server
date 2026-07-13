package app.ister.core.eventdata;

import app.ister.core.enums.SubtitleFormat;
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
public class TranscodeRequestedData extends MessageData {
    private UUID mediaFileId;
    private Boolean direct;
    private Boolean transcode;
    private SubtitleFormat subtitleFormat;
    private Boolean preTranscode;
    /** Keep the HLS cache for this media file at least until this moment (epoch millis). */
    private Long keepUntilEpochMillis;
    /**
     * Audio languages worth pre-transcoding, from the settings of the users this request was made
     * for. Null or empty means every audio stream — which is what an interactive request wants.
     */
    private List<String> audioLanguages;
    /** Highest video variant to pre-transcode (720 / 480); null means every variant. */
    private Integer maxVideoHeight;
}
