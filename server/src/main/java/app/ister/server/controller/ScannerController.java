package app.ister.server.controller;

import app.ister.server.enums.EventType;
import app.ister.server.events.newdirectoriesscanrequested.NewDirectoriesScanRequestedData;
import app.ister.server.service.MessageSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

@Controller
public class ScannerController {
    @Autowired
    private MessageSender messageSender;

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public void scanLibrary() {
        messageSender.sendNewDirectoriesScanRequested(
                NewDirectoriesScanRequestedData.builder()
                        .eventType(EventType.NEW_DIRECTORIES_SCAN_REQUEST).build());
    }
}
