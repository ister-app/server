package app.ister.core.node;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

@Component
public class RemoteNodeClient {

    /** Without these bounds an unresponsive peer node hangs the upload watcher thread forever. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final NodeTokenManager nodeTokenManager;
    private final HttpClient httpClient;

    @Autowired
    public RemoteNodeClient(NodeTokenManager nodeTokenManager) {
        this(nodeTokenManager, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    RemoteNodeClient(NodeTokenManager nodeTokenManager, HttpClient httpClient) {
        this.nodeTokenManager = nodeTokenManager;
        this.httpClient = httpClient;
    }

    /** Pushes an HLS artefact into {@code tmpDir/{mediaFileId}} on the node that will serve it. */
    public void uploadFile(String nodeUrl, UUID mediaFileId, Path file) throws IOException {
        post(nodeUrl + "/transcode/upload/" + mediaFileId + "/" + file.getFileName(), file);
    }

    /**
     * Pushes a cache artefact (an extracted subtitle) into the owning node's cache directory,
     * where the database row will point at it.
     */
    public void uploadToCache(String nodeUrl, Path file) throws IOException {
        post(nodeUrl + "/cache/upload/" + file.getFileName(), file);
    }

    /** Downloads a tokenized URL (already carrying its {@code ?token=}) to {@code target}. */
    public void downloadToFile(String url, Path target) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                Files.deleteIfExists(target);
                throw new IOException("Download failed: HTTP " + response.statusCode() + " for " + MediaFileInputResolver.stripToken(url));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    private void post(String urlWithoutToken, Path file) throws IOException {
        String url = urlWithoutToken + "?token=" + nodeTokenManager.getUploadToken();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofFile(file))
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Upload failed: HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Upload interrupted", e);
        }
    }
}
