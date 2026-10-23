package app.ister.worker.config;

import feign.http2client.Http2Client;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class FeignConfigTest {

    /**
     * Feign's default client (HttpURLConnection) reads the socket on the listener's virtual thread,
     * which the native image can leave parked for the whole read timeout; TMDB must go through the
     * JDK HTTP client.
     */
    @Test
    void tmdbUsesTheJdkHttpClient() {
        assertInstanceOf(Http2Client.class, new FeignConfig().feignClient());
    }
}
