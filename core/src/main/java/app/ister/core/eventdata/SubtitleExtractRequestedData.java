package app.ister.core.eventdata;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * Requests the extraction of one embedded text subtitle stream to an SRT in the owning node's
 * cache directory. One message per stream, so a message is bounded by a single ffmpeg run and
 * RabbitMQ can share a file's streams between the owner and a helper node. Fired after a media
 * file's analysis commits. Bitmap subtitles get no such event: the transcoder serves them as
 * sprite sheets at playback time.
 */
@EqualsAndHashCode(callSuper = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class SubtitleExtractRequestedData extends MessageData {
    private UUID mediaFileEntityUUID;
    private UUID directoryEntityUUID;
    private UUID subtitleStreamEntityUUID;
}
