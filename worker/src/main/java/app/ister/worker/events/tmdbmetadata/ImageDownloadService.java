package app.ister.worker.events.tmdbmetadata;

import app.ister.core.enums.ImageType;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.storage.CacheStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageDownloadService {
    private final CacheDirectoryResolver cacheDirectoryResolver;
    private final ImageDownload imageDownload;
    private final ImageSave imageSave;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    public void downloadAndSave(String imageUrl, ImageType imageType, String language,
                                String sourceUri,
                                ImageSave.MediaEntityRef ref) throws IOException {
        CacheStore cacheStore = cacheDirectoryResolver.store();
        String relativeKey = UUID.randomUUID() + ".jpg";
        // Downloaded to tmp first: the cache may be a bucket, and the store wants a finished file.
        Files.createDirectories(Path.of(tmpDir));
        Path downloaded = Files.createTempFile(Path.of(tmpDir), "artwork-", ".jpg");
        try {
            imageDownload.download(imageUrl, downloaded.toString());
            String storedPath = cacheStore.write(relativeKey, downloaded, "image/jpeg");
            imageSave.save(cacheStore.directory(), storedPath, imageType, language, sourceUri, ref);
        } finally {
            Files.deleteIfExists(downloaded);
        }
    }
}
