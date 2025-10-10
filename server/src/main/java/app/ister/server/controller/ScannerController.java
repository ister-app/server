package app.ister.server.controller;

import app.ister.server.enums.EventType;
import app.ister.server.events.analyzelibraryrequested.AnalyzeLibraryRequestedData;
import app.ister.server.events.newdirectoriesscanrequested.NewDirectoriesScanRequestedData;
import app.ister.server.service.MessageSender;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

@Controller
public class ScannerController {
    private final MessageSender messageSender;

    public ScannerController(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean scanLibrary() {
        messageSender.sendNewDirectoriesScanRequested(
                NewDirectoriesScanRequestedData.builder()
                        .eventType(EventType.NEW_DIRECTORIES_SCAN_REQUEST).build());
        return true;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean analyzeLibrary() {
        messageSender.sendAnalyzeLibraryRequested(
                AnalyzeLibraryRequestedData.builder()
                        .eventType(EventType.ANALYZE_LIBRARY_REQUEST).build());
        return true;
    }
}
