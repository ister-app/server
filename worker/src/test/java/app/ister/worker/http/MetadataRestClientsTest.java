package app.ister.worker.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataRestClientsTest {

    /**
     * A metadata client without a read timeout parks a RabbitMQ listener thread — and its open
     * transaction — forever on a provider that accepts the connection and never answers, so the
     * timeouts must be set explicitly. The factory must be the JDK one: the listener thread is
     * virtual, and a client reading the socket on it can stall for its whole read timeout in the
     * native image (GraalVM poller bug). Redirects must be followed, as HttpURLConnection did.
     */
    @Test
    void clientIsBuiltOnTheJdkHttpClientWithConnectAndReadTimeouts() {
        RestClient client = MetadataRestClients.json();

        ClientHttpRequestFactory factory =
                (ClientHttpRequestFactory) ReflectionTestUtils.getField(client, "clientRequestFactory");
        assertNotNull(factory);
        JdkClientHttpRequestFactory jdk = assertInstanceOf(JdkClientHttpRequestFactory.class, factory);

        HttpClient httpClient = (HttpClient) ReflectionTestUtils.getField(jdk, "httpClient");
        assertNotNull(httpClient);
        assertTrue(httpClient.connectTimeout().isPresent(), "connect timeout must be bounded");
        assertEquals(Duration.ofSeconds(5), httpClient.connectTimeout().get());
        assertEquals(HttpClient.Redirect.NORMAL, httpClient.followRedirects());
        assertEquals(Duration.ofSeconds(15), ReflectionTestUtils.getField(jdk, "readTimeout"));
    }
}
