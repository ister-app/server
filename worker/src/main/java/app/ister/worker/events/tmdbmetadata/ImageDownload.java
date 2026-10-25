package app.ister.worker.events.tmdbmetadata;

import app.ister.core.http.JdkHttpClients;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Downloads an image to a file with the JDK HTTP client. {@code URLConnection} read the socket on
 * the listener's virtual thread, which the native image can leave parked for the whole read timeout
 * (see {@link JdkHttpClients}). As before, a non-2xx answer is an {@link IOException} for the caller.
 */
@Component
public class ImageDownload {
    // Wikimedia image hosts (Commons Special:FilePath, upload.wikimedia.org — used for artist
    // photos) reject the default Java user agent with HTTP 403, so send a descriptive one.
    private static final String USER_AGENT = "IsterServer/1.0 (info@ister.app)";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

    private static final HttpClient HTTP_CLIENT = JdkHttpClients.httpClient(CONNECT_TIMEOUT);

    public void download(String imageUrl, String toPath) throws IOException {
        download(imageUrl, toPath, REQUEST_TIMEOUT);
    }

    /** With a caller's own bound on the whole request, for downloads that are only nice to have. */
    public void download(String imageUrl, String toPath, Duration timeout) throws IOException {
        Path target = Path.of(toPath);
        HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl))
                .header("User-Agent", USER_AGENT)
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<Path> response;
        try {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(target));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + imageUrl, e);
        }
        if (response.statusCode() >= 400) {
            Files.deleteIfExists(target);
            throw new IOException("Downloading " + imageUrl + " failed with HTTP " + response.statusCode());
        }
    }
}
