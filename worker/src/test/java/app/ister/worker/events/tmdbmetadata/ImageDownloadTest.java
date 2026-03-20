package app.ister.worker.events.tmdbmetadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageDownloadTest {

    @TempDir
    Path tempDir;

    @Test
    void downloadFromFileUrl() throws IOException {
        Path sourceFile = tempDir.resolve("source.jpg");
        Files.writeString(sourceFile, "fake image content");
        Path destFile = tempDir.resolve("dest.jpg");

        ImageDownload subject = new ImageDownload();
        subject.download(sourceFile.toUri().toString(), destFile.toString());

        assertTrue(Files.exists(destFile));
        assertTrue(Files.size(destFile) > 0);
    }

    @Test
    void throwsIOExceptionForNonexistentSourceFile() {
        ImageDownload subject = new ImageDownload();
        Path destFile = tempDir.resolve("dest.jpg");
        assertThrows(IOException.class, () ->
                subject.download("file:///nonexistent/path/image.jpg", destFile.toString()));
    }
}
