package app.ister.worker.events.tmdbmetadata;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageDownloadTest {

    private static final byte[] IMAGE = "fake image content".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicReference<String> userAgent = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image.jpg", exchange -> {
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            exchange.sendResponseHeaders(200, IMAGE.length);
            try (var body = exchange.getResponseBody()) {
                body.write(IMAGE);
            }
        });
        server.createContext("/moved.jpg", exchange -> {
            exchange.getResponseHeaders().add("Location", "/image.jpg");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/missing.jpg", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    void downloadsToFileWithDescriptiveUserAgent() throws IOException {
        Path destFile = tempDir.resolve("dest.jpg");

        new ImageDownload().download(url("/image.jpg"), destFile.toString());

        assertArrayEquals(IMAGE, Files.readAllBytes(destFile));
        assertEquals("IsterServer/1.0 (info@ister.app)", userAgent.get());
    }

    /** Commons Special:FilePath and Cover Art Archive answer with a redirect to the real file. */
    @Test
    void followsRedirects() throws IOException {
        Path destFile = tempDir.resolve("dest.jpg");

        new ImageDownload().download(url("/moved.jpg"), destFile.toString());

        assertArrayEquals(IMAGE, Files.readAllBytes(destFile));
    }

    @Test
    void throwsIOExceptionAndLeavesNoFileForAnErrorStatus() {
        Path destFile = tempDir.resolve("dest.jpg");

        IOException e = assertThrows(IOException.class, () ->
                new ImageDownload().download(url("/missing.jpg"), destFile.toString()));

        assertTrue(e.getMessage().contains("404"), e.getMessage());
        assertFalse(Files.exists(destFile));
    }
}
