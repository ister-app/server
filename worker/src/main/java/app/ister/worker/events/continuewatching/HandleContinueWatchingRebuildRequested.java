package app.ister.worker.events.continuewatching;

import app.ister.core.Handle;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.ContinueWatchingRebuildRequestedData;
import app.ister.core.repository.UserRepository;
import app.ister.core.service.ContinueWatchingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_CONTINUE_WATCHING_REBUILD_REQUESTED;

/**
 * Rebuilds one user's continue-watching list from their watch history. The nightly repair of a
 * cache that is otherwise maintained incrementally by the playback heartbeat, so a watch status
 * written outside that path (or media that disappeared) cannot leave a stale entry behind for long.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HandleContinueWatchingRebuildRequested implements Handle<ContinueWatchingRebuildRequestedData> {

    private final UserRepository userRepository;
    private final ContinueWatchingService continueWatchingService;

    @Override
    public EventType handles() {
        return EventType.CONTINUE_WATCHING_REBUILD_REQUESTED;
    }

    @RabbitListener(queues = APP_ISTER_SERVER_CONTINUE_WATCHING_REBUILD_REQUESTED)
    @Override
    public void listener(ContinueWatchingRebuildRequestedData data) {
        Handle.super.listener(data);
    }

    @Override
    public void handle(ContinueWatchingRebuildRequestedData data) {
        Optional<UserEntity> user = userRepository.findById(data.getUserId());
        if (user.isEmpty()) {
            log.debug("No user {} to rebuild continue watching for", data.getUserId());
            return;
        }
        continueWatchingService.rebuildForUser(user.get());
    }
}
