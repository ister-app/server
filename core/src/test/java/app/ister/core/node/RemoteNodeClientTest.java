package app.ister.core.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        when(tokenManager.getUploadToken()).thenReturn("test-token");

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
        when(tokenManager.getUploadToken()).thenReturn("test-token");

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
        when(tokenManager.getUploadToken()).thenReturn("test-token");

        HttpClient httpClient = mock(HttpClient.class);
        doThrow(new InterruptedException("interrupted")).when(httpClient).send(any(HttpRequest.class), any());

        RemoteNodeClient client = new RemoteNodeClient(tokenManager, httpClient);

        Path file = tempDir.resolve("segment.ts");
        Files.writeString(file, "data");

        assertThrows(IOException.class, () ->
                client.uploadFile("http://remote:8080", UUID.randomUUID(), file));
    }

    @Test
    void uploadToCacheTargetsTheCacheUploadEndpoint() throws Exception {
        NodeTokenManager tokenManager = mock(NodeTokenManager.class);
        when(tokenManager.getUploadToken()).thenReturn("test-token");
        HttpClient httpClient = mock(HttpClient.class);
        doReturn(mockResponse(200)).when(httpClient).send(any(HttpRequest.class), any());
        RemoteNodeClient client = new RemoteNodeClient(tokenManager, httpClient);
        Path file = tempDir.resolve("abc_2_eng.srt");
        Files.writeString(file, "1\n");

        client.uploadToCache("http://remote:8080", file);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());
        assertEquals("http://remote:8080/cache/upload/abc_2_eng.srt?token=test-token", captor.getValue().uri().toString());
    }

    @Test
    void downloadToFileDeletesThePartialFileOnFailure() throws Exception {
        NodeTokenManager tokenManager = mock(NodeTokenManager.class);
        HttpClient httpClient = mock(HttpClient.class);
        Path target = tempDir.resolve("sub.srt");
        doAnswer(inv -> {
            Files.writeString(target, "partial");
            return mockResponse(404);
        }).when(httpClient).send(any(HttpRequest.class), any());
        RemoteNodeClient client = new RemoteNodeClient(tokenManager, httpClient);

        assertThrows(IOException.class, () -> client.downloadToFile("http://remote:8080/mediaFileStream/x/download?token=t", target));
        assertFalse(Files.exists(target));
    }
}
