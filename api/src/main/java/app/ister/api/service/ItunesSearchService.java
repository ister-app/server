package app.ister.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Podcast directory search via the free iTunes Search API (no key). Pure pass-through: nothing is
 * persisted; subscribing happens with the returned feed URL.
 */
@Slf4j
@Service
public class ItunesSearchService {
    private static final String USER_AGENT = "IsterServer/1.0 (info@ister.app)";

    private final String itunesBase;
    private final RestClient restClient;

    public ItunesSearchService(
            @Value("${app.ister.api.podcast.itunes-base:https://itunes.apple.com}") String itunesBase) {
        this.itunesBase = itunesBase;
        this.restClient = RestClient.builder()
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    public record DirectoryResult(String name, String author, String feedUrl, String artworkUrl) {
    }

    public List<DirectoryResult> search(String term, int limit) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(itunesBase + "/search?media=podcast&limit={limit}&term={term}", limit, term)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !(response.get("results") instanceof List<?> results)) {
                return List.of();
            }
            return results.stream()
                    .filter(Map.class::isInstance)
                    .map(result -> (Map<?, ?>) result)
                    .filter(result -> result.get("feedUrl") instanceof String)
                    .map(result -> new DirectoryResult(
                            asString(result.get("collectionName")),
                            asString(result.get("artistName")),
                            asString(result.get("feedUrl")),
                            asString(result.get("artworkUrl600"))))
                    .toList();
        } catch (RestClientException e) {
            log.warn("iTunes podcast search failed for term={}: {}", term, e.getMessage());
            return List.of();
        }
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
