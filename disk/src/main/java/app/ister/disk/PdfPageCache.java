package app.ister.disk;

import app.ister.disk.events.comicfilefound.PdfParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk cache for rasterized pdf comic pages, following the HLS segment cache's pattern:
 * exists-or-generate under {@code tmp-dir/{mediaFileId}/} with a per-file lock against
 * duplicate renders and a last-modified touch on every hit so the tmp cleanup's idle sweep
 * keeps hot volumes alive. Deliberately under tmp-dir, not cache-dir: the cache-dir cleanup
 * deletes files no database row references, which rendered pages never have.
 */
@Slf4j
@Component
public class PdfPageCache {

    private final PdfParser pdfParser;
    private final String tmpDir;

    private final ConcurrentHashMap<Path, Object> renderLocks = new ConcurrentHashMap<>();

    public PdfPageCache(PdfParser pdfParser, @Value("${app.ister.server.tmp-dir}") String tmpDir) {
        this.pdfParser = pdfParser;
        this.tmpDir = tmpDir;
    }

    /**
     * The cached render of one pdf page at {@code width} pixels wide, generating it on a miss;
     * empty when rendering fails (broken page, or a native image without AWT).
     */
    public Optional<Path> pageJpeg(UUID mediaFileId, Path pdfPath, int index, int width) throws IOException {
        Path cacheFile = Path.of(tmpDir, mediaFileId.toString(), "comic-page-%d-w%d.jpg".formatted(index, width));
        if (Files.exists(cacheFile)) {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()));
            return Optional.of(cacheFile);
        }
        synchronized (renderLocks.computeIfAbsent(cacheFile, k -> new Object())) {
            if (Files.exists(cacheFile)) {
                return Optional.of(cacheFile);
            }
            Optional<byte[]> rendered = pdfParser.renderPageJpeg(pdfPath, index, width);
            if (rendered.isEmpty()) {
                return Optional.empty();
            }
            Files.createDirectories(cacheFile.getParent());
            Path part = cacheFile.resolveSibling(cacheFile.getFileName() + ".part");
            Files.write(part, rendered.get());
            Files.move(part, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(cacheFile);
        }
    }
}
