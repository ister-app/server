package app.ister.server.controller;

import app.ister.server.service.TranscodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Controller
public class TranscoderController {
    @Autowired
    private TranscodeService transcodeService;

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public boolean stopTranscoding(@Argument UUID id) {
        return transcodeService.stopTranscoding(id);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public boolean readyTranscoding(@Argument UUID id) {
        return transcodeService.readyTranscoding(id);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public UUID startTranscoding(@Argument UUID playQueueId, @Argument UUID mediaFileId, @Argument int startTimeInSeconds, @Argument Optional<UUID> audioId, @Argument Optional<UUID> subtitleId) throws IOException {
        return transcodeService.startTranscoding(playQueueId, mediaFileId, startTimeInSeconds, audioId, subtitleId);
    }
}
