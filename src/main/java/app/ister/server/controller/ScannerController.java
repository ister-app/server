package app.ister.server.controller;

import app.ister.server.enums.DiskType;
import app.ister.server.repository.*;
import app.ister.server.scanner.LibraryScanner;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("scanner")
@SecurityRequirement(name = "oidc_auth")
public class ScannerController {
    @Autowired
    private LibraryScanner libraryScanner;

    @Autowired
    private DiskRepository diskRepository;

    @GetMapping(value = "/scan")
    public void scan() {
        diskRepository.findAll().forEach(disk1 -> {
            if (disk1.getDiskType().equals(DiskType.LIBRARY)) {
                try {
                    libraryScanner.scanDiskForCategorie(disk1);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}
