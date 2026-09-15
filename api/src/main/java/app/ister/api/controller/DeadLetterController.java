package app.ister.api.controller;

import app.ister.core.service.DeadLetterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/** Admin view on the dead-letter queue: how many events failed for good, and a way to retry them. */
@Slf4j
@Controller
@RequiredArgsConstructor
public class DeadLetterController {
    private final DeadLetterService deadLetterService;

    @QueryMapping
    @PreAuthorize("hasRole('admin')")
    public Integer deadLetterCount() {
        return deadLetterService.count();
    }

    @MutationMapping
    @PreAuthorize("hasRole('admin')")
    public Integer replayDeadLetters() {
        log.info("replayDeadLetters requested");
        return deadLetterService.replay();
    }
}
