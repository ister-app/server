package app.ister.transcoder;

import app.ister.core.eventdata.TranscodeActivityStatusData;
import app.ister.core.eventdata.TranscodeActivityStatusData.TranscodePass;
import app.ister.core.service.MessageSender;
import app.ister.core.status.ActivitySubjects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes this node's running FFmpeg transcode passes on the status exchange.
 * Transcodes bypass the RabbitMQ work queues (HlsTranscodeService runs them on its own
 * pool), so ProcessingActivityAdvice never sees them — without this publisher the
 * activity screen shows an "idle" server that is transcoding at 100% CPU.
 * <p>
 * Model follows NodeActivityPublisher: poll every 2s, publish on change, keepalive
 * every 30s while passes run so TranscodeActivityRegistry's sweeper (90s expiry) keeps
 * live entries. The busy->idle transition publishes one final empty list, which the
 * registry treats as removal.
 */
@Component
public class TranscodeActivityPublisher {

    static final long KEEPALIVE_MS = 30_000;
    private static final int TITLE_CACHE_MAX = 100;

    private final HlsTranscodeService transcodeService;
    private final MessageSender messageSender;
    private final String nodeName;
    private final MediaFileSubjects mediaFileSubjects;

    /** mediaFileId (string) -> description; LRU so a long prefetch queue can't grow it unbounded. */
    private final Map<String, ActivitySubjects.Subject> titleCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ActivitySubjects.Subject> eldest) {
            return size() > TITLE_CACHE_MAX;
        }
    };

    private List<TranscodePass> lastPasses = List.of();
    private long lastPublishedAtMillis;

    public TranscodeActivityPublisher(HlsTranscodeService transcodeService, MessageSender messageSender,
                                      MediaFileSubjects mediaFileSubjects,
                                      @Value("${app.ister.server.name}") String nodeName) {
        this.transcodeService = transcodeService;
        this.messageSender = messageSender;
        this.mediaFileSubjects = mediaFileSubjects;
        this.nodeName = nodeName;
    }

    @Scheduled(fixedDelay = 2000)
    public void publishIfChanged() {
        List<TranscodePass> passes = transcodeService.runningPassesSnapshot().stream()
                .map(pass -> {
                    ActivitySubjects.Subject subject = subjectFor(pass.mediaFileId());
                    return new TranscodePass(pass.mediaFileId(),
                            subject == null ? null : subject.title(),
                            qualityOf(pass.generationKey(), pass.mediaFileId()), pass.background(),
                            Instant.ofEpochMilli(pass.startedAtMillis()),
                            subject == null ? null : subject.context(),
                            subject == null ? null : subject.contextType(),
                            subject == null ? null : subject.contextId());
                })
                .toList();
        long nowMillis = System.currentTimeMillis();
        boolean unchanged = passes.equals(lastPasses);
        if (unchanged && passes.isEmpty()) {
            return; // Idle, and the empty transition (if any) was already published.
        }
        if (unchanged && nowMillis - lastPublishedAtMillis < KEEPALIVE_MS) {
            return;
        }
        messageSender.sendStatus(new TranscodeActivityStatusData(nodeName, Instant.now(), passes));
        lastPasses = passes;
        lastPublishedAtMillis = nowMillis;
    }

    /** The pass's slice of the generation key: "{mediaFileId}_video_720p" -> "video_720p". */
    private static String qualityOf(String generationKey, String mediaFileId) {
        return generationKey.startsWith(mediaFileId + "_")
                ? generationKey.substring(mediaFileId.length() + 1)
                : generationKey;
    }

    private ActivitySubjects.Subject subjectFor(String mediaFileId) {
        synchronized (titleCache) {
            ActivitySubjects.Subject cached = titleCache.get(mediaFileId);
            if (cached != null) {
                return cached;
            }
        }
        // File name plus show/movie/album context; empty for non-UUID pass keys or a missing row.
        ActivitySubjects.Subject subject = mediaFileSubjects.describe(mediaFileId).orElse(null);
        if (subject != null) {
            synchronized (titleCache) {
                titleCache.put(mediaFileId, subject);
            }
        }
        return subject;
    }
}
