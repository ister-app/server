package app.ister.server.entitiy;

import app.ister.server.Transcoder;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Getter
@Builder
public class TranscodeSessionEntity {
    private UUID id;
    private UUID playQueueId;
    private String dir;
    private int startTimeInSeconds;
    private MediaFileEntity mediaFile;
    private Transcoder transcoder;
    private AtomicBoolean stopped;
    private AtomicBoolean paused;
    private AtomicLong progressTimeInMilliseconds;
    private AtomicLong pid;
}
