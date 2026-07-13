package app.ister.worker.http;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Builder for the clients that talk to the external metadata providers (MusicBrainz, Open Library,
 * Wikidata/Wikipedia).
 * <p>
 * The timeouts are the point. These calls run on {@code @RabbitListener} threads (3 of them) inside
 * an open transaction, so a provider that accepts the connection and then never answers would park a
 * listener thread — and its Hibernate session and pooled connection — indefinitely, silently
 * stalling the queue. A bounded wait turns that into a logged failure and a retryable event.
 * <p>
 * The User-Agent is equally load-bearing: Wikimedia hosts answer the default Java agent with 403.
 */
public final class MetadataRestClients {

    private static final String USER_AGENT = "IsterServer/1.0 (info@ister.app)";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private MetadataRestClients() {
    }

    public static RestClient json() {
        return RestClient.builder()
                .requestFactory(requestFactory())
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    private static ClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
