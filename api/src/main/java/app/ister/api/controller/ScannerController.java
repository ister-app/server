package app.ister.api.controller;

import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.NewDirectoriesScanRequestedData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.service.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ScannerController {
    private final MessageSender messageSender;
    private final DirectoryRepository directoryRepository;

    /** Scans for newly added files, optionally limited to one library's directories. */
    @PreAuthorize("hasRole('admin')")
    @MutationMapping
    public Boolean scanLibraries(@Argument UUID libraryId) {
        log.debug("Start scanLibraries, libraryId: {}", libraryId);
        var directories = libraryId == null
                ? directoryRepository.findByDirectoryType(DirectoryType.LIBRARY)
                : directoryRepository.findByDirectoryTypeAndLibraryEntityId(DirectoryType.LIBRARY, libraryId);
        directories.forEach(directory -> {
            log.debug("sendNewDirectoriesScanRequested: {}", directory.getName());
            messageSender.sendNewDirectoriesScanRequested(
                    NewDirectoriesScanRequestedData.builder()
                            .eventType(EventType.NEW_DIRECTORIES_SCAN_REQUEST)
                            .directoryEntityUUID(directory.getId())
                            .build(),
                    directory.getName());
        });
        return true;
    }
}
