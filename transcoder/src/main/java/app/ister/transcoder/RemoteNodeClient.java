package app.ister.transcoder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    public void uploadFile(String nodeUrl, UUID mediaFileId, Path file) throws IOException {
        String url = nodeUrl + "/transcode/upload/" + mediaFileId + "/"
                + file.getFileName() + "?token=" + nodeTokenManager.getUploadToken();
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
