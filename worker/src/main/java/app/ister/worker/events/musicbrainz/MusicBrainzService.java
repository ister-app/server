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
    private static final String WIKIPEDIA_API_BASE = "https://en.wikipedia.org/api/rest_v1/page/summary";
    private static final String USER_AGENT = "IsterServer/1.0 (info@ister.app)";
    private static final String ARTIST_QUERY_PREFIX = "artist:";

    private final RestClient restClient;

    public MusicBrainzService() {
        this.restClient = RestClient.builder()
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * type is MusicBrainz's artist type ("Person", "Group", ...); lifeSpanBegin is the
     * begin date of the artist's life-span (birth date for persons, founding date for
     * groups), possibly just a year ("1946") or year-month.
     */
    public record ArtistInfo(String bio, String genre, String imageUrl, String type, String lifeSpanBegin) {}

    public record AlbumInfo(String description) {}

    public Optional<String> getCoverArtUrl(String artistName, String albumName) {
        try {
            Thread.sleep(1000);
            String query = ARTIST_QUERY_PREFIX + encode(artistName) + " AND release:" + encode(albumName);
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
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("MusicBrainz API error for artist={} album={}: {}", artistName, albumName, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<ArtistInfo> getArtistInfo(String artistName) {
        try {
            Thread.sleep(1000);
            String query = ARTIST_QUERY_PREFIX + encode(artistName);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(MUSICBRAINZ_BASE + "/artist?query={query}&fmt=json&limit=1&inc=annotation+url-rels+tags", query)
                    .retrieve()
                    .body(Map.class);

            if (response == null) return Optional.empty();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> artists = (List<Map<String, Object>>) response.get("artists");
            if (artists == null || artists.isEmpty()) {
                log.debug("No MusicBrainz artist found for name={}", artistName);
                return Optional.empty();
            }

            Map<String, Object> artist = artists.getFirst();
            String bio = extractAnnotation(artist);
            String genre = extractTopTag(artist);
            String imageUrl = extractWikipediaImageUrl(artist);
            String type = artist.get("type") instanceof String s ? s : null;
            String lifeSpanBegin = extractLifeSpanBegin(artist);

            if (bio == null && genre == null && imageUrl == null) return Optional.empty();
            return Optional.of(new ArtistInfo(bio, genre, imageUrl, type, lifeSpanBegin));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("MusicBrainz API error for artist={}: {}", artistName, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<AlbumInfo> getAlbumInfo(String artistName, String albumName) {
        try {
            Thread.sleep(1000);
            String query = ARTIST_QUERY_PREFIX + encode(artistName) + " AND releasegroup:" + encode(albumName);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(MUSICBRAINZ_BASE + "/release-group?query={query}&fmt=json&limit=1&inc=annotation", query)
                    .retrieve()
                    .body(Map.class);

            if (response == null) return Optional.empty();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) response.get("release-groups");
            if (groups == null || groups.isEmpty()) {
                log.debug("No MusicBrainz release-group found for artist={} album={}", artistName, albumName);
                return Optional.empty();
            }

            String description = extractAnnotation(groups.getFirst());
            if (description == null) return Optional.empty();
            return Optional.of(new AlbumInfo(description));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("MusicBrainz API error for artist={} album={}: {}", artistName, albumName, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private String extractAnnotation(Map<String, Object> entity) {
        Object annotation = entity.get("annotation");
        if (annotation instanceof Map<?, ?> ann) {
            Object text = ((Map<String, Object>) ann).get("text");
            if (text instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractLifeSpanBegin(Map<String, Object> entity) {
        Object lifeSpan = entity.get("life-span");
        if (lifeSpan instanceof Map<?, ?> span) {
            Object begin = ((Map<String, Object>) span).get("begin");
            if (begin instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractTopTag(Map<String, Object> entity) {
        Object tags = entity.get("tags");
        if (tags instanceof List<?> tagList && !tagList.isEmpty()) {
            Object first = tagList.getFirst();
            if (first instanceof Map<?, ?> tag) {
                Object name = ((Map<String, Object>) tag).get("name");
                if (name instanceof String s && !s.isBlank()) return s;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractWikipediaImageUrl(Map<String, Object> entity) {
        Object relations = entity.get("relations");
        if (!(relations instanceof List<?> relList)) return null;

        for (Object rel : relList) {
            String resource = wikipediaResource(rel);
            if (resource == null) continue;

            // Extract the page title from the Wikipedia URL
            String title = resource.substring(resource.lastIndexOf('/') + 1);
            return fetchWikipediaThumbnail(title);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String wikipediaResource(Object rel) {
        if (!(rel instanceof Map<?, ?> relMap)) return null;
        Map<String, Object> relation = (Map<String, Object>) relMap;
        if (!"wikipedia".equals(relation.get("type"))) return null;
        Object urlObj = relation.get("url");
        if (!(urlObj instanceof Map<?, ?> urlMap)) return null;
        return (String) ((Map<String, Object>) urlMap).get("resource");
    }

    private String fetchWikipediaThumbnail(String pageTitle) {
        try {
            Thread.sleep(500);
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = restClient.get()
                    .uri(WIKIPEDIA_API_BASE + "/{title}", pageTitle)
                    .retrieve()
                    .body(Map.class);

            if (summary == null) return null;
            Object thumbnail = summary.get("thumbnail");
            if (!(thumbnail instanceof Map<?, ?> thumb)) return null;
            @SuppressWarnings("unchecked")
            Object source = ((Map<String, Object>) thumb).get("source");
            return source instanceof String s ? s : null;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RestClientException e) {
            log.debug("Wikipedia thumbnail fetch failed for {}: {}", pageTitle, e.getMessage());
            return null;
        }
    }

    private static String encode(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
