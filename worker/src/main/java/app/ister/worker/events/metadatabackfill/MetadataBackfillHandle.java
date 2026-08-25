package app.ister.worker.events.metadatabackfill;

import app.ister.core.Handle;
import app.ister.core.MessageQueue;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.MetadataBackfillRequestedData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

/**
 * Handles {@link EventType#METADATA_BACKFILL_REQUESTED}: re-dispatches {@code *_FOUND} events for
 * every item missing metadata, artwork or TMDB enrichment, optionally scoped to one library.
 *
 * <p>The queue is global — one consumer wins, so the backfill runs exactly once cluster-wide
 * (it used to run per node, which duplicated all globally-queried work on multi-node installs).
 * The blur-hash sweep is not part of this event: the API controller sends UPDATE_IMAGES_REQUESTED
 * per directory directly, since those queues are already directory-scoped.
 *
 * <p>Deliberately not one long transaction: each service step opens its own (the fan-out only
 * emits events; a partially completed backfill is harmless and the next run finishes it).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MetadataBackfillHandle implements Handle<MetadataBackfillRequestedData> {

    private final MetadataBackfillService metadataBackfillService;

    @RabbitListener(queues = MessageQueue.APP_ISTER_SERVER_METADATA_BACKFILL_REQUESTED)
    @Override
    public void listener(MetadataBackfillRequestedData data) {
        Handle.super.listener(data);
    }

    @Override
    public EventType handles() {
        return EventType.METADATA_BACKFILL_REQUESTED;
    }

    @Override
    public void handle(MetadataBackfillRequestedData data) {
        var libraryId = data.getLibraryId();
        log.debug("Starting metadata backfill, libraryId: {}", libraryId);
        metadataBackfillService.dispatchMissingVideoMetadataEvents(libraryId);
        metadataBackfillService.dispatchMissingPersonMetadataEvents(libraryId);
        metadataBackfillService.dispatchMissingMusicMetadataEvents(libraryId);
        metadataBackfillService.dispatchMissingBookMetadataEvents(libraryId);
        metadataBackfillService.applyBookSeriesHeuristics(libraryId);
        metadataBackfillService.dispatchBookSeriesNfoEvents(libraryId);
        metadataBackfillService.dispatchMissingComicMetadataEvents(libraryId);
    }
}
