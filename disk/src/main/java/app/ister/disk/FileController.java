package app.ister.disk;

import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
//@SecurityRequirement(name = "oidc_auth")
public class FileController {
    private final ImageRepository imageRepository;
    private final MediaFileRepository mediaFileRepository;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    @GetMapping("/images/{id}/download")
    public InputStreamResource downloadImage(@PathVariable UUID id) throws IOException {
        var imageEntity = imageRepository.findById(id).orElseThrow();
        return new InputStreamResource(new FileInputStream(imageEntity.getPath())) {
            @Override
            public long contentLength() throws IOException {
                return Files.size(Path.of(imageEntity.getPath()));
            }
        };
    }

    @GetMapping("/transcode/download/{id}/{fileName}")
    public InputStreamResource downloadTranscode(@PathVariable UUID id, @PathVariable String fileName) throws IOException {
        String filePath = Path.of(tmpDir, id.toString(), fileName).toString();
        return new InputStreamResource(new FileInputStream(filePath)) {
            @Override
            public long contentLength() throws IOException {
                return Files.size(Path.of(filePath));
            }
        };
    }

    @GetMapping("/mediaFile/{id}/download")
    public InputStreamResource downloadMediaFile(@PathVariable UUID id) throws IOException {
        var mediaFileEntity = mediaFileRepository.findById(id).orElseThrow();
        return new InputStreamResource(new FileInputStream(mediaFileEntity.getPath())) {
            @Override
            public long contentLength() throws IOException {
                return Files.size(Path.of(mediaFileEntity.getPath()));
            }
        };
    }
}
