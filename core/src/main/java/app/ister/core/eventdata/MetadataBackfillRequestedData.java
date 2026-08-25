package app.ister.core.eventdata;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * Requests the metadata backfill: re-dispatch every item that is missing metadata, artwork or TMDB
 * enrichment. Global event — exactly one worker consumes it, so the backfill runs once cluster-wide.
 */
@EqualsAndHashCode(callSuper = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class MetadataBackfillRequestedData extends MessageData {
    /** Optional: narrow the backfill to one library; null means all libraries. */
    private UUID libraryId;
}
