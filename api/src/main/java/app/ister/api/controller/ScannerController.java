package app.ister.api.controller;

import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.AnalyzeLibraryRequestedData;
import app.ister.core.eventdata.NewDirectoriesScanRequestedData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.NodeRepository;
import app.ister.core.service.MessageSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

@Slf4j
@Controller
public class ScannerController {
    private final MessageSender messageSender;
    private final NodeRepository nodeRepository;
    private final DirectoryRepository directoryRepository;

    public ScannerController(MessageSender messageSender, NodeRepository nodeRepository, DirectoryRepository directoryRepository) {
        this.messageSender = messageSender;
        this.nodeRepository = nodeRepository;
        this.directoryRepository = directoryRepository;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean scanLibrary() {
        log.debug("Start scanLibrary");
        directoryRepository.findByDirectoryType(DirectoryType.LIBRARY).forEach(directory -> {
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

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean analyzeLibrary() {
        log.debug("start analyzeLibrary");
        nodeRepository.findAll().forEach(node ->
                messageSender.sendAnalyzeLibraryRequested(
                        AnalyzeLibraryRequestedData.builder()
                                .eventType(EventType.ANALYZE_LIBRARY_REQUEST).build(),
                        node.getName()));
        return true;
    }
}
