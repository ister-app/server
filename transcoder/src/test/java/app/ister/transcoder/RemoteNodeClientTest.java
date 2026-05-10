package app.ister.transcoder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RemoteNodeClientTest {

    @TempDir
    Path tempDir;

    @SuppressWarnings("unchecked")
    private HttpResponse<Void> mockResponse(int statusCode) {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        return response;
    }

    @Test
    void publicConstructorCreatesInstance() {
        NodeTokenManager tokenManager = mock(NodeTokenManager.class);
        RemoteNodeClient client = new RemoteNodeClient(tokenManager);
        assertNotNull(client);
    }

    @Test
    void uploadFileSucceedsOnHttp200() throws Exception {
        NodeTokenManager tokenManager = mock(NodeTokenManager.class);
        when(tokenManager.getToken()).thenReturn("test-token");

        HttpClient httpClient = mock(HttpClient.class);
        doReturn(mockResponse(200)).when(httpClient).send(any(HttpRequest.class), any());

        RemoteNodeClient client = new RemoteNodeClient(tokenManager, httpClient);

        Path file = tempDir.resolve("segment.ts");
        Files.writeString(file, "data");

        client.uploadFile("http://remote:8080", UUID.randomUUID(), file);

        verify(httpClient).send(any(HttpRequest.class), any());
    }

    @Test
    void uploadFileThrowsOnNon2xxStatus() throws Exception {
        NodeTokenManager tokenManager = mock(NodeTokenManager.class);
        when(tokenManager.getToken()).thenReturn("test-token");

        HttpClient httpClient = mock(HttpClient.class);
        doReturn(mockResponse(500)).when(httpClient).send(any(HttpRequest.class), any());

        RemoteNodeClient client = new RemoteNodeClient(tokenManager, httpClient);

        Path file = tempDir.resolve("segment.ts");
        Files.writeString(file, "data");

        assertThrows(IOException.class, () ->
                client.uploadFile("http://remote:8080", UUID.randomUUID(), file));
    }

    @Test
    void uploadFileThrowsIOExceptionOnInterrupt() throws Exception {
        NodeTokenManager tokenManager = mock(NodeTokenManager.class);
        when(tokenManager.getToken()).thenReturn("test-token");

        HttpClient httpClient = mock(HttpClient.class);
        doThrow(new InterruptedException("interrupted")).when(httpClient).send(any(HttpRequest.class), any());

        RemoteNodeClient client = new RemoteNodeClient(tokenManager, httpClient);

        Path file = tempDir.resolve("segment.ts");
        Files.writeString(file, "data");

        assertThrows(IOException.class, () ->
                client.uploadFile("http://remote:8080", UUID.randomUUID(), file));
    }
}
