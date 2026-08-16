package app.ister.transcoder;

import app.ister.core.eventdata.TranscodeActivityStatusData;
import app.ister.core.eventdata.TranscodeActivityStatusData.TranscodePass;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.MessageSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
    private final MediaFileRepository mediaFileRepository;
    private final TransactionTemplate readOnlyTransaction;

    /** mediaFileId (string) -> display title; LRU so a long prefetch queue can't grow it unbounded. */
    private final Map<String, String> titleCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > TITLE_CACHE_MAX;
        }
    };

    private List<TranscodePass> lastPasses = List.of();
    private long lastPublishedAtMillis;

    public TranscodeActivityPublisher(HlsTranscodeService transcodeService, MessageSender messageSender,
                                      MediaFileRepository mediaFileRepository,
                                      PlatformTransactionManager transactionManager,
                                      @Value("${app.ister.server.name}") String nodeName) {
        this.transcodeService = transcodeService;
        this.messageSender = messageSender;
        this.mediaFileRepository = mediaFileRepository;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.nodeName = nodeName;
    }

    @Scheduled(fixedDelay = 2000)
    public void publishIfChanged() {
        List<TranscodePass> passes = transcodeService.runningPassesSnapshot().stream()
                .map(pass -> new TranscodePass(pass.mediaFileId(), titleFor(pass.mediaFileId()),
                        qualityOf(pass.generationKey(), pass.mediaFileId()), pass.background(),
                        Instant.ofEpochMilli(pass.startedAtMillis())))
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

    private String titleFor(String mediaFileId) {
        synchronized (titleCache) {
            String cached = titleCache.get(mediaFileId);
            if (cached != null) {
                return cached;
            }
        }
        String title = lookupTitle(mediaFileId).orElse(null);
        if (title != null) {
            synchronized (titleCache) {
                titleCache.put(mediaFileId, title);
            }
        }
        return title;
    }

    /** Basename of the media file's path; empty for non-UUID pass keys or a missing row. */
    private Optional<String> lookupTitle(String mediaFileId) {
        UUID id;
        try {
            id = UUID.fromString(mediaFileId);
        } catch (IllegalArgumentException _) {
            return Optional.empty(); // Not a UUID-keyed pass; leave the title empty.
        }
        return Optional.ofNullable(readOnlyTransaction.execute(status ->
                mediaFileRepository.findById(id)
                        .map(file -> Path.of(file.getPath()).getFileName().toString())
                        .orElse(null)));
    }
}
