package app.ister.server.controller;

import app.ister.server.entitiy.ServerEventEntity;
import app.ister.server.enums.EventType;
import app.ister.server.repository.ServerEventRepository;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("scanner")
@SecurityRequirement(name = "oidc_auth")
public class ScannerController {
    @Autowired
    private ServerEventRepository serverEventRepository;

    @GetMapping(value = "/scan")
    public void scan() {
        serverEventRepository.save(
                ServerEventEntity.builder()
                    .eventType(EventType.NEW_DIRECTORIES_SCAN_REQUEST).build());
    }
}
