package app.ister.core.eventdata;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * Requests intro/outro detection for a season. Fired after an episode file finishes analysis and
 * by the scanner's backfill; the queue is directory-scoped so detection runs on the node owning
 * the season's files. Season-level (not per-file) because intros are found by comparing sibling
 * episodes; the handler is idempotent, so one event per analyzed episode is fine.
 */
@EqualsAndHashCode(callSuper = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class DetectSegmentsData extends MessageData {
    private UUID seasonEntityUUID;
    private UUID directoryEntityUUID;
}
