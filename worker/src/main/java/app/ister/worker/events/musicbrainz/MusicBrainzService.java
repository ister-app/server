package app.ister.worker.events.musicbrainz;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Component
public class MusicBrainzService {

    private static final String MUSICBRAINZ_BASE = "https://musicbrainz.org/ws/2";
    private static final String COVER_ART_BASE = "https://coverartarchive.org/release";
    private static final String COVER_ART_RELEASE_GROUP_BASE = "https://coverartarchive.org/release-group";
    private static final String WIKIDATA_ENTITY_BASE = "https://www.wikidata.org/wiki/Special:EntityData/";
    private static final int IMAGE_WIDTH = 1000;
    private static final String USER_AGENT = "IsterServer/1.0 (info@ister.app)";
    private static final String ARTIST_QUERY_PREFIX = "artist:";
    private static final int MAX_ATTEMPTS = 3;

    /** Base URL for downloadable Wikimedia Commons files (Special:FilePath). */
    private final String commonsFilepathBase;
    private final RestClient restClient;

    public MusicBrainzService(
            @Value("${app.ister.worker.musicbrainz.commons-filepath-base:https://commons.wikimedia.org/wiki/Special:FilePath/}")
            String commonsFilepathBase) {
        this.commonsFilepathBase = commonsFilepathBase;
        this.restClient = RestClient.builder()
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * type is MusicBrainz's artist type ("Person", "Group", ...); lifeSpanBegin is the
     * begin date of the artist's life-span (birth date for persons, founding date for
     * groups), possibly just a year ("1946") or year-month. {@code bios} holds a biography per
     * requested language tag (from Wikipedia via Wikidata); it may be empty.
     */
    public record ArtistInfo(Map<String, String> bios, String genre, String imageUrl, String type, String lifeSpanBegin) {}

    private record WikiSummary(String extract, String thumbnail) {}

    /** Extra artist data that only the MBID lookup (not the search endpoint) provides. */
    private record ArtistDetails(String annotationBio, String genre, String imageUrl, String wikidataId) {
        private static final ArtistDetails EMPTY = new ArtistDetails(null, null, null, null);
    }

    /** Per-language Wikipedia bios plus the first available thumbnail (image fallback). */
    private record WikipediaContent(Map<String, String> bios, String thumbnail) {}

    public record AlbumInfo(String description) {}

    public Optional<String> getCoverArtUrl(String artistName, String albumName) {
        String normalizedAlbum = normalizeAlbumName(albumName);
        // The album is queried as a quoted phrase but deliberately not field-scoped, because a
        // field-scoped exact match fails on stylized canonical titles — e.g. our "Emotion" vs
        // MusicBrainz's "E•MO•TION", where the interpunct splits tokens so "emotion" matches nothing.
        // The general phrase query lets MusicBrainz's analyzer normalise punctuation and rank the
        // album first, and a normalised-title check on the results guards against loose false positives.
        String query = ARTIST_QUERY_PREFIX + encode(artistName) + " AND " + encode(normalizedAlbum);

        // Prefer the release-group front: coverartarchive returns art if ANY release in the group has
        // a cover, instead of gambling on one specific release that often has none uploaded.
        Optional<String> releaseGroupId = findMatchingId(
                "/release-group?query={query}&fmt=json&limit=5", "release-groups",
                query, artistName, normalizedAlbum);
        if (releaseGroupId.isPresent()) {
            return releaseGroupId.map(id -> COVER_ART_RELEASE_GROUP_BASE + "/" + id + "/front");
        }
        // Fall back to a specific release match.
        return findMatchingId(
                "/release?query={query}&fmt=json&limit=5", "releases",
                query, artistName, normalizedAlbum)
                .map(id -> COVER_ART_BASE + "/" + id + "/front");
    }

    /**
     * Runs a MusicBrainz search and returns the id of the first result whose title matches
     * {@code expectedTitle} after normalisation (case, punctuation and stylisation removed), so a
     * loose phrase query can surface stylized titles without accepting an unrelated near-match.
     * Retries on HTTP 503 (MusicBrainz rate limiting) instead of giving up on the first hiccup.
     */
    private Optional<String> findMatchingId(String uriTemplate, String resultsKey, String query,
                                            String artistName, String expectedTitle) {
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
                return matchOnTitle(response, resultsKey, artistName, expectedTitle);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (HttpServerErrorException.ServiceUnavailable _) {
                // MusicBrainz throttles with HTTP 503; the 1s sleep at the top of the loop backs off.
                log.warn("MusicBrainz {} rate-limited (attempt {}/{}) for artist={} album={}",
                        resultsKey, attempt, MAX_ATTEMPTS, artistName, expectedTitle);
                if (attempt == MAX_ATTEMPTS) {
                    return Optional.empty();
                }
            } catch (RestClientException e) {
                log.warn("MusicBrainz {} error for artist={} album={}: {}", resultsKey, artistName, expectedTitle, e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Id of the first result in {@code response} whose title equals {@code expectedTitle} after
     * normalisation, or empty when there are no results or none matches. */
    private Optional<String> matchOnTitle(Map<String, Object> response, String resultsKey,
                                          String artistName, String expectedTitle) {
        String wanted = normalizeTitle(expectedTitle);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get(resultsKey);
        if (results == null || results.isEmpty()) {
            log.debug("No MusicBrainz {} found for artist={} album={}", resultsKey, artistName, expectedTitle);
            return Optional.empty();
        }
        Optional<String> match = results.stream()
                .filter(r -> wanted.equals(normalizeTitle((String) r.get("title"))))
                .map(r -> (String) r.get("id"))
                .filter(Objects::nonNull)
                .findFirst();
        if (match.isEmpty()) {
            log.debug("MusicBrainz {} results for artist={} album={} did not match on title",
                    resultsKey, artistName, expectedTitle);
        }
        return match;
    }

    /** Lowercases and removes everything that is not a letter or digit, collapsing stylisation
     * (interpunct, accents-as-punctuation, spacing) so "E•MO•TION" and "Emotion" compare equal.
     * Public: OpenLibraryService reuses the same match rule. */
    public static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
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
        String normalized = stripTrailingSeparators(name
                .replaceAll("[(\\[][^)\\]]*[)\\]]", " ")
                .replaceAll("(?i)\\b(flac|deluxe(\\s+edition)?|special\\s+edition|remastered|remaster|bonus\\s+tracks?)\\b", " ")
                .replaceAll("\\s{2,}", " ")
                .strip());
        return normalized.isEmpty() ? name.strip() : normalized;
    }

    /**
     * Drops the separator run a stripped suffix leaves behind ("Album - FLAC" → "Album -" → "Album").
     * A char loop instead of a "[-\s]+$" regex, whose scan is super-linear on long separator runs.
     */
    private static String stripTrailingSeparators(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '-' || Character.isWhitespace(value.charAt(end - 1)))) {
            end--;
        }
        return value.substring(0, end);
    }

    public Optional<ArtistInfo> getArtistInfo(String artistName, List<String> languageTags) {
        Map<String, Object> artist = searchTopArtist(artistName);
        if (artist.isEmpty()) {
            return Optional.empty();
        }
        // Guard against a wrong artist: if MusicBrainz returned a name, require it to match ours
        // after normalisation (case/punctuation/stylisation removed).
        String foundName = artist.get("name") instanceof String s ? s : null;
        if (foundName != null && !normalizeTitle(artistName).equals(normalizeTitle(foundName))) {
            log.debug("MusicBrainz top artist for name={} did not match (got {})", artistName, foundName);
            return Optional.empty();
        }

        String type = artist.get("type") instanceof String s ? s : null;
        String lifeSpanBegin = extractLifeSpanBegin(artist);

        ArtistDetails details = lookupArtistDetails(artist);
        String genre = extractTopTag(artist);
        if (genre == null) {
            genre = details.genre();
        }

        WikipediaContent wiki = fetchWikipediaContent(details.wikidataId(), languageTags);
        // The MusicBrainz image relation wins over the Wikipedia thumbnail fallback.
        String imageUrl = details.imageUrl() != null ? details.imageUrl() : wiki.thumbnail();

        Map<String, String> bios = wiki.bios();
        // Fall back to the (rare) MusicBrainz annotation for the primary language if Wikipedia gave nothing.
        if (bios.isEmpty() && details.annotationBio() != null && languageTags != null && !languageTags.isEmpty()) {
            bios.put(languageTags.getFirst(), details.annotationBio());
        }

        // Return whenever the artist was found, so the birth year (life-span) flows through even for
        // artists that have no bio/genre/image — that year is what links them to TMDB actors.
        return Optional.of(new ArtistInfo(bios, genre, imageUrl, type, lifeSpanBegin));
    }

    /** The /artist search endpoint ignores inc=annotation+url-rels, so the annotation and the
     * image/wikidata links only come from an MBID lookup. */
    private ArtistDetails lookupArtistDetails(Map<String, Object> artist) {
        if (!(artist.get("id") instanceof String mbid)) {
            return ArtistDetails.EMPTY;
        }
        Map<String, Object> details = lookupArtist(mbid);
        return new ArtistDetails(extractAnnotation(details), extractTopTag(details),
                extractImageRelationUrl(details), extractWikidataId(details));
    }

    /** Bio per language + a thumbnail image fallback come from Wikidata → Wikipedia. MusicBrainz
     * annotations are rare, and artists link via wikidata (not a direct wikipedia relation). */
    private WikipediaContent fetchWikipediaContent(String wikidataId, List<String> languageTags) {
        Map<String, String> bios = new LinkedHashMap<>();
        if (wikidataId == null || languageTags == null || languageTags.isEmpty()) {
            return new WikipediaContent(bios, null);
        }
        Map<String, Object> wikidata = fetchWikidataEntity(wikidataId);
        String thumbnail = null;
        for (String tag : languageTags) {
            WikiSummary summary = fetchSummaryForLanguage(wikidata, wikidataId, tag);
            if (summary == null) {
                continue;
            }
            if (summary.extract() != null && !summary.extract().isBlank()) {
                bios.put(tag, summary.extract());
            }
            if (thumbnail == null && summary.thumbnail() != null) {
                thumbnail = summary.thumbnail();
            }
        }
        return new WikipediaContent(bios, thumbnail);
    }

    /** Wikipedia summary for one language via the entity's sitelink, or null when the entity has
     * no wiki in that language (or the summary fetch failed). */
    private WikiSummary fetchSummaryForLanguage(Map<String, Object> wikidata, String wikidataId, String tag) {
        String title = sitelinkTitle(wikidata, wikidataId, tag);
        return title == null ? null : fetchWikipediaSummary(tag, title);
    }

    /** Top /artist search result, or an empty map when nothing was found. */
    private Map<String, Object> searchTopArtist(String artistName) {
        Map<String, Object> response = musicBrainzGet(
                "/artist?query={query}&fmt=json&limit=1&inc=tags", ARTIST_QUERY_PREFIX + encode(artistName),
                "artist search", artistName);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artists = (List<Map<String, Object>>) response.get("artists");
        if (artists == null || artists.isEmpty()) {
            log.debug("No MusicBrainz artist found for name={}", artistName);
            return Map.of();
        }
        return artists.getFirst();
    }

    private Map<String, Object> lookupArtist(String mbid) {
        return musicBrainzGet("/artist/{mbid}?fmt=json&inc=annotation+url-rels+tags", mbid, "artist lookup", mbid);
    }

    /** GETs a MusicBrainz JSON document, retrying on HTTP 503 (rate limiting). Returns an empty
     * map on repeated rate limiting, other client/server errors, or a null body. */
    private Map<String, Object> musicBrainzGet(String uriTemplate, String pathArg, String what, String context) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(1000);
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restClient.get()
                        .uri(MUSICBRAINZ_BASE + uriTemplate, pathArg)
                        .retrieve()
                        .body(Map.class);
                return response == null ? Map.of() : response;
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return Map.of();
            } catch (HttpServerErrorException.ServiceUnavailable _) {
                log.warn("MusicBrainz {} rate-limited (attempt {}/{}) for {}", what, attempt, MAX_ATTEMPTS, context);
                if (attempt == MAX_ATTEMPTS) {
                    return Map.of();
                }
            } catch (RestClientException e) {
                log.warn("MusicBrainz {} error for {}: {}", what, context, e.getMessage());
                return Map.of();
            }
        }
        return Map.of();
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

    /** URL of the MusicBrainz "image" relation (a Wikimedia Commons file page), rewritten to a
     * downloadable, width-capped Special:FilePath URL. */
    @SuppressWarnings("unchecked")
    private String extractImageRelationUrl(Map<String, Object> entity) {
        String resource = relationResource(entity, "image");
        if (resource == null) {
            return null;
        }
        // resource looks like https://commons.wikimedia.org/wiki/File:Name.jpg
        int marker = resource.indexOf("/wiki/File:");
        if (marker < 0) {
            return null;
        }
        String fileName = resource.substring(marker + "/wiki/File:".length());
        return commonsFilepathBase + fileName + "?width=" + IMAGE_WIDTH;
    }

    /** Wikidata entity id (e.g. "Q151892") from the MusicBrainz "wikidata" relation. */
    private String extractWikidataId(Map<String, Object> entity) {
        String resource = relationResource(entity, "wikidata");
        if (resource == null) {
            return null;
        }
        String id = resource.substring(resource.lastIndexOf('/') + 1);
        return id.startsWith("Q") ? id : null;
    }

    @SuppressWarnings("unchecked")
    private String relationResource(Map<String, Object> entity, String relationType) {
        Object relations = entity.get("relations");
        if (!(relations instanceof List<?> relList)) {
            return null;
        }
        for (Object rel : relList) {
            if (!(rel instanceof Map<?, ?> relMap) || !relationType.equals(relMap.get("type"))) {
                continue;
            }
            if (((Map<String, Object>) relMap).get("url") instanceof Map<?, ?> urlMap
                    && ((Map<String, Object>) urlMap).get("resource") instanceof String resource) {
                return resource;
            }
        }
        return null;
    }

    /** Wikidata entity document, or an empty map when the fetch failed or the body was null. */
    private Map<String, Object> fetchWikidataEntity(String wikidataId) {
        try {
            Thread.sleep(500);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.get()
                    .uri(WIKIDATA_ENTITY_BASE + "{id}.json", wikidataId)
                    .retrieve()
                    .body(Map.class);
            return body == null ? Map.of() : body;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (RestClientException e) {
            log.debug("Wikidata fetch failed for {}: {}", wikidataId, e.getMessage());
            return Map.of();
        }
    }

    /** Title of the {@code <tag>wiki} sitelink (e.g. "enwiki", "nlwiki") for a Wikidata entity. */
    @SuppressWarnings("unchecked")
    private String sitelinkTitle(Map<String, Object> wikidata, String wikidataId, String languageTag) {
        if (!(wikidata.get("entities") instanceof Map<?, ?> entities)
                || !(((Map<String, Object>) entities).get(wikidataId) instanceof Map<?, ?> entity)
                || !(((Map<String, Object>) entity).get("sitelinks") instanceof Map<?, ?> sitelinks)) {
            return null;
        }
        Object link = ((Map<String, Object>) sitelinks).get(languageTag + "wiki");
        if (link instanceof Map<?, ?> linkMap && ((Map<String, Object>) linkMap).get("title") instanceof String title) {
            return title;
        }
        return null;
    }

    private WikiSummary fetchWikipediaSummary(String languageTag, String pageTitle) {
        try {
            Thread.sleep(500);
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = restClient.get()
                    .uri("https://{lang}.wikipedia.org/api/rest_v1/page/summary/{title}", languageTag, pageTitle)
                    .retrieve()
                    .body(Map.class);
            if (summary == null) {
                return null;
            }
            Object extract = summary.get("extract");
            String extractText = extract instanceof String s && !s.isBlank() ? s : null;
            String thumbnail = null;
            if (summary.get("thumbnail") instanceof Map<?, ?> thumb
                    && ((Map<String, Object>) thumb).get("source") instanceof String src) {
                thumbnail = src;
            }
            return new WikiSummary(extractText, thumbnail);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RestClientException e) {
            log.debug("Wikipedia summary fetch failed for {}/{}: {}", languageTag, pageTitle, e.getMessage());
            return null;
        }
    }

    private static String encode(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
