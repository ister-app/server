package app.ister.core.config;

import app.ister.core.http.JdkHttpClients;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Fetches the OIDC discovery document and the JWKS with the JDK HTTP client instead of Spring
 * Security's default {@code RestTemplate} (HttpURLConnection, 30 s timeouts). The default reads the
 * socket on the request's virtual thread, which the GraalVM poller bug can leave parked for the full
 * 30 s while every other request waits up to Nimbus' 15 s cache-refresh timeout and then fails with
 * 401 (see {@link JdkHttpClients}). Spring Boot applies this customizer on the {@code issuer-uri}
 * path too, so the auto-configured decoder, and with it the {@code audienceValidator} bean and the
 * websocket authentication, stay as they are.
 * <p>
 * The read timeout stays below Nimbus' 15 s refresh timeout so a genuinely hanging Keycloak costs
 * the waiting requests no more than it does today.
 */
@Configuration
public class JwkSetHttpClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    JwkSetUriJwtDecoderBuilderCustomizer jwkSetUriJdkHttpClient() {
        return builder -> builder.restOperations(
                new RestTemplate(JdkHttpClients.requestFactory(CONNECT_TIMEOUT, READ_TIMEOUT)));
    }
}
