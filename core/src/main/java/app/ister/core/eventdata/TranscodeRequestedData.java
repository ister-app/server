package app.ister.core.eventdata;

import app.ister.core.enums.SubtitleFormat;
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
public class TranscodeRequestedData extends MessageData {
    private UUID mediaFileId;
    private Boolean direct;
    private Boolean transcode;
    private SubtitleFormat subtitleFormat;
}
