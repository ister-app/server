package app.ister.worker.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataRestClientsTest {

    /**
     * A metadata client without a read timeout parks a RabbitMQ listener thread — and its open
     * transaction — forever on a provider that accepts the connection and never answers. The
     * default request factory waits indefinitely, so the timeouts must be set explicitly.
     */
    @Test
    void clientIsBuiltWithConnectAndReadTimeouts() {
        RestClient client = MetadataRestClients.json();

        ClientHttpRequestFactory factory =
                (ClientHttpRequestFactory) ReflectionTestUtils.getField(client, "clientRequestFactory");
        assertNotNull(factory);
        SimpleClientHttpRequestFactory simple = assertInstanceOf(SimpleClientHttpRequestFactory.class, factory);

        int connectTimeout = (int) ReflectionTestUtils.getField(simple, "connectTimeout");
        int readTimeout = (int) ReflectionTestUtils.getField(simple, "readTimeout");
        assertTrue(connectTimeout > 0, "connect timeout must be bounded");
        assertTrue(readTimeout > 0, "read timeout must be bounded");
        assertEquals(5_000, connectTimeout);
        assertEquals(15_000, readTimeout);
    }
}
