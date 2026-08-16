package app.ister.core.eventdata;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Periodic snapshot of the FFmpeg transcode passes running on a node, published on the
 * status fan-out exchange. Transcodes run outside the RabbitMQ work queues (see
 * HlsTranscodeService), so without this they would be invisible on the activity screen.
 * Not a Handle-pattern event, so it does not extend MessageData.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscodeActivityStatusData {
    private String nodeName;
    private Instant timestamp;
    private List<TranscodePass> passes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TranscodePass {
        private String mediaFileId;
        /** Display name of the media file (basename of its path); null when the row is gone. */
        private String title;
        /** The pass's slice of the generation key, e.g. "video_720p" or "audio_0_128k". */
        private String quality;
        private boolean background;
        private Instant startedAt;
    }
}
