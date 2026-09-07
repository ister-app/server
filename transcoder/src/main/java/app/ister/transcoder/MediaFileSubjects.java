package app.ister.transcoder;

import app.ister.core.repository.MediaFileRepository;
import app.ister.core.status.ActivitySubjects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * Looks a media file up and describes it for the activity screen, inside a read-only
 * transaction.
 * <p>
 * ActivitySubjects walks lazy associations (directory → library, episode → show, …), so it
 * needs an open Hibernate session. RabbitMQ listener threads do not have one, and the
 * transcoder's handlers are deliberately not {@code @Transactional}: they run FFmpeg for the
 * length of a movie, which no database transaction should span. So the session is opened
 * around the lookup and the describe only, and closed again before the actual work starts.
 * Every transcoder caller must go through here — describing a media file straight off the
 * repository dies with a LazyInitializationException and dead-letters the event.
 */
@Component
@Slf4j
public class MediaFileSubjects {

    private final MediaFileRepository mediaFileRepository;
    private final TransactionTemplate readOnlyTransaction;

    public MediaFileSubjects(MediaFileRepository mediaFileRepository, PlatformTransactionManager transactionManager) {
        this.mediaFileRepository = mediaFileRepository;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    /**
     * The activity subject for a media file; empty when the id is null or the row is gone.
     * <p>
     * Never throws: the description is what the activity screen shows, so a failure here must
     * not fail the transcode that was asked for.
     */
    public Optional<ActivitySubjects.Subject> describe(UUID mediaFileId) {
        if (mediaFileId == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(readOnlyTransaction.execute(status ->
                    mediaFileRepository.findById(mediaFileId).map(ActivitySubjects::describe).orElse(null)));
        } catch (RuntimeException e) {
            log.warn("Could not describe mediaFileId={} for the activity screen", mediaFileId, e);
            return Optional.empty();
        }
    }

    /**
     * The subject for a pass key's media file id; empty for a non-UUID key or a missing row.
     * Pass keys are strings because not every pass is keyed by a media file.
     */
    public Optional<ActivitySubjects.Subject> describe(String mediaFileId) {
        try {
            return describe(mediaFileId == null ? null : UUID.fromString(mediaFileId));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
