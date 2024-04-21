package app.ister.server.controller;

import app.ister.server.repository.ImageRepository;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

@RestController
@SecurityRequirement(name = "oidc_auth")
public class FileController {
    @Autowired
    private ImageRepository imageRepository;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    @RequestMapping(value = "/images/{id}/download", method = RequestMethod.GET)
    public InputStreamResource download(@PathVariable UUID id) throws IOException {
        var imageEntity = imageRepository.findById(id).orElseThrow();
        return new InputStreamResource(new FileInputStream(imageEntity.getPath())) {
            @Override
            public long contentLength() throws IOException {
                return Files.size(Paths.get(imageEntity.getPath()));
            }
        };
    }

    @RequestMapping(value = "/transcode/download/{id}/{fileName}", method = RequestMethod.GET)
    public InputStreamResource download(@PathVariable UUID id, @PathVariable String fileName) throws IOException {
        String filePath = tmpDir + id + "/" + fileName;
        return new InputStreamResource(new FileInputStream(filePath)) {
            @Override
            public long contentLength() throws IOException {
                return Files.size(Paths.get(filePath));
            }
        };
    }
}
