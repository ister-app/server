package app.ister.worker.events.musicbrainz;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
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
    private static final String COVER_ART_RELEASE_GROUP_BASE = "https://coverartarchive.org/release-group";
    private static final String WIKIPEDIA_API_BASE = "https://en.wikipedia.org/api/rest_v1/page/summary";
    private static final String USER_AGENT = "IsterServer/1.0 (info@ister.app)";
    private static final String ARTIST_QUERY_PREFIX = "artist:";
    private static final int MAX_ATTEMPTS = 3;

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
        String normalizedAlbum = normalizeAlbumName(albumName);
        // Prefer the release-group front: coverartarchive returns art if ANY release in the group has
        // a cover, instead of gambling on one specific release (limit=1) that often has none uploaded.
        Optional<String> releaseGroupId = findId(
                "/release-group?query={query}&fmt=json&limit=1", "release-groups",
                ARTIST_QUERY_PREFIX + encode(artistName) + " AND releasegroup:" + encode(normalizedAlbum),
                artistName, albumName);
        if (releaseGroupId.isPresent()) {
            return releaseGroupId.map(id -> COVER_ART_RELEASE_GROUP_BASE + "/" + id + "/front");
        }
        // Fall back to a specific release match.
        return findId(
                "/release?query={query}&fmt=json&limit=1", "releases",
                ARTIST_QUERY_PREFIX + encode(artistName) + " AND release:" + encode(normalizedAlbum),
                artistName, albumName)
                .map(id -> COVER_ART_BASE + "/" + id + "/front");
    }

    /**
     * Runs a MusicBrainz search and returns the id of the first result under {@code resultsKey}.
     * Retries a couple of times on transient errors (MusicBrainz rate-limits with HTTP 503) instead
     * of silently giving up on the first hiccup.
     */
    private Optional<String> findId(String uriTemplate, String resultsKey, String query, String artistName, String albumName) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(1000);
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restClient.get()
                        .uri(MUSICBRAINZ_BASE + uriTemplate, query)
                        .retrieve()
                        .body(Map.class);
                if (response == null) {
                    return Optional.empty();
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> results = (List<Map<String, Object>>) response.get(resultsKey);
                if (results == null || results.isEmpty()) {
                    log.debug("No MusicBrainz {} found for artist={} album={}", resultsKey, artistName, albumName);
                    return Optional.empty();
                }
                return Optional.ofNullable((String) results.getFirst().get("id"));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (HttpServerErrorException.ServiceUnavailable e) {
                // MusicBrainz throttles with HTTP 503; the 1s sleep at the top of the loop backs off.
                log.warn("MusicBrainz {} rate-limited (attempt {}/{}) for artist={} album={}",
                        resultsKey, attempt, MAX_ATTEMPTS, artistName, albumName);
                if (attempt == MAX_ATTEMPTS) {
                    return Optional.empty();
                }
            } catch (RestClientException e) {
                log.warn("MusicBrainz {} error for artist={} album={}: {}", resultsKey, artistName, albumName, e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Strips edition/format noise that MusicBrainz's canonical titles don't carry, so that folder
     * names like "Album (Deluxe Edition)", "Album (RE 2005)" or "Album FLAC" still match. Never
     * returns empty: if stripping removes everything, the original (trimmed) name is kept.
     */
    static String normalizeAlbumName(String name) {
        if (name == null) {
            return "";
        }
        String normalized = name
                .replaceAll("[(\\[][^)\\]]*[)\\]]", " ")
                .replaceAll("(?i)\\b(flac|deluxe(\\s+edition)?|special\\s+edition|remastered|remaster|bonus\\s+tracks?)\\b", " ")
                .replaceAll("(?i)[-\\s]+flac\\b", " ")
                .replaceAll("\\s{2,}", " ")
                .strip();
        return normalized.isEmpty() ? name.strip() : normalized;
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
