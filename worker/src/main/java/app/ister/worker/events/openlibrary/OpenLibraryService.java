package app.ister.worker.events.openlibrary;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static app.ister.worker.events.musicbrainz.MusicBrainzService.normalizeTitle;

/**
 * Book metadata enrichment via Open Library (no API key). Modeled on MusicBrainzService: search
 * for the work by title+author, verify the match on a normalized title, then fetch the work's
 * description and cover. Local sources (epub OPF, nfo, cover.jpg) are primary; this only fills
 * gaps.
 */
@Slf4j
@Component
public class OpenLibraryService {

    private static final String USER_AGENT = "IsterServer/1.0 (info@ister.app)";
    private static final String COVERS_BASE = "https://covers.openlibrary.org/b/id/";

    private final String openLibraryBase;
    private final RestClient restClient;

    public OpenLibraryService(
            @Value("${app.ister.worker.openlibrary.base:https://openlibrary.org}") String openLibraryBase) {
        this.openLibraryBase = openLibraryBase;
        this.restClient = RestClient.builder()
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * @param description the work description, or null
     * @param coverUrl    a large cover image URL, or null
     * @param firstPublishYear the first publication year, or 0 when unknown
     */
    public record BookInfo(String description, String coverUrl, int firstPublishYear) {}

    public Optional<BookInfo> getBookInfo(String title, String authorName) {
        Optional<Map<String, Object>> doc = searchWork(title, authorName);
        if (doc.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> work = doc.get();
        String coverUrl = work.get("cover_i") instanceof Number coverId
                ? COVERS_BASE + coverId.longValue() + "-L.jpg"
                : null;
        int firstPublishYear = work.get("first_publish_year") instanceof Number year ? year.intValue() : 0;
        String description = work.get("key") instanceof String key ? fetchDescription(key) : null;
        if (description == null && coverUrl == null && firstPublishYear == 0) {
            return Optional.empty();
        }
        return Optional.of(new BookInfo(description, coverUrl, firstPublishYear));
    }

    /** First search result whose title matches ours after normalisation. */
    private Optional<Map<String, Object>> searchWork(String title, String authorName) {
        try {
            Thread.sleep(1000);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(openLibraryBase + "/search.json?title={title}&author={author}&limit=5&fields=key,title,author_name,cover_i,first_publish_year",
                            title, authorName)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !(response.get("docs") instanceof List<?> docs)) {
                return Optional.empty();
            }
            String wanted = normalizeTitle(title);
            for (Object doc : docs) {
                if (doc instanceof Map<?, ?> docMap
                        && wanted.equals(normalizeTitle((String) docMap.get("title")))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> match = (Map<String, Object>) docMap;
                    return Optional.of(match);
                }
            }
            log.debug("No Open Library work matched title={} author={}", title, authorName);
            return Optional.empty();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Open Library search error for title={} author={}: {}", title, authorName, e.getMessage());
            return Optional.empty();
        }
    }

    /** The work's description ("/works/OL...W"); either a plain string or {type,value}. */
    private String fetchDescription(String workKey) {
        try {
            Thread.sleep(1000);
            @SuppressWarnings("unchecked")
            Map<String, Object> work = restClient.get()
                    .uri(openLibraryBase + workKey + ".json")
                    .retrieve()
                    .body(Map.class);
            if (work == null) {
                return null;
            }
            Object description = work.get("description");
            if (description instanceof String s && !s.isBlank()) {
                return s.strip();
            }
            if (description instanceof Map<?, ?> descMap
                    && descMap.get("value") instanceof String value && !value.isBlank()) {
                return value.strip();
            }
            return null;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RestClientException e) {
            log.warn("Open Library work fetch error for {}: {}", workKey, e.getMessage());
            return null;
        }
    }
}
