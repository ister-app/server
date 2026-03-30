package app.ister.transcoder;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RemoteNodeClient {

    private final NodeTokenManager nodeTokenManager;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public void uploadFile(String nodeUrl, UUID mediaFileId, Path file) throws IOException {
        String url = nodeUrl + "/transcode/upload/" + mediaFileId + "/"
                + file.getFileName() + "?token=" + nodeTokenManager.getToken();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
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
