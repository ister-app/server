package app.ister.worker.events.musicbrainz;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class MusicBrainzService {

    private static final String MUSICBRAINZ_BASE = "https://musicbrainz.org/ws/2";
    private static final String COVER_ART_BASE = "https://coverartarchive.org/release";
    private static final String USER_AGENT = "IsterServer/1.0 (info@ister.app)";

    private final RestClient restClient;

    public MusicBrainzService() {
        this.restClient = RestClient.builder()
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    public Optional<String> getCoverArtUrl(String artistName, String albumName) {
        try {
            Thread.sleep(1000);
            String query = "artist:" + encode(artistName) + " AND release:" + encode(albumName);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(MUSICBRAINZ_BASE + "/release?query={query}&fmt=json&limit=1", query)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return Optional.empty();
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> releases = (List<Map<String, Object>>) response.get("releases");
            if (releases == null || releases.isEmpty()) {
                log.debug("No MusicBrainz release found for artist={} album={}", artistName, albumName);
                return Optional.empty();
            }

            String mbid = (String) releases.getFirst().get("id");
            if (mbid == null) {
                return Optional.empty();
            }

            return Optional.of(COVER_ART_BASE + "/" + mbid + "/front");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("MusicBrainz API error for artist={} album={}: {}", artistName, albumName, e.getMessage());
            return Optional.empty();
        }
    }

    private static String encode(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
