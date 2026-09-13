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
public class TranscodePassRequestedData extends MessageData {
    private UUID mediaFileId;
    /**
     * URL of the node the client is talking to. An S3 directory has no owner, so the node that
     * picks up the work pushes the produced segments/playlists here when it is not that node
     * itself. Null (older producers, or local playback) = the transcoding node serves them.
     */
    private String requestingNodeUrl;
    private String passKey;
    private String mediaFilePath;
    private String passCategory;
    private String qualityLabel;
    private Integer audioStreamIndex;
    /** True for pre-transcode/prefetch work; background passes never delay interactive playback. */
    private Boolean background;
}
