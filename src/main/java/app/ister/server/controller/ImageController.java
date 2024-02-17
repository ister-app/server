package app.ister.server.controller;

import app.ister.server.entitiy.ImageEntity;
import app.ister.server.repository.ImageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

@RestController
@RequestMapping("images")
public class ImageController {
    @Autowired
    private ImageRepository imageRepository;

    @GetMapping(value = "/")
    public Page<ImageEntity> getRecent() {
        return imageRepository.findAll(PageRequest.of(0, 10));
    }

    @RequestMapping(value = "/{id}/download", method = RequestMethod.GET)
    public InputStreamResource download(@PathVariable UUID id) throws IOException {
        var imageEntity = imageRepository.findById(id).orElseThrow();
        return new InputStreamResource(new FileInputStream(imageEntity.getPath())) {
            @Override
            public long contentLength() throws IOException {
                return Files.size(Paths.get(imageEntity.getPath()));
            }
        };
    }
}
