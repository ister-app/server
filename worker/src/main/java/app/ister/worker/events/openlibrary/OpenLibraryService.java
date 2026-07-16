package app.ister.worker.events.openlibrary;

import app.ister.worker.events.wikipedia.WikipediaService;
import app.ister.worker.http.MetadataRestClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static app.ister.worker.events.musicbrainz.MusicBrainzService.namesMatch;
import static app.ister.worker.events.musicbrainz.MusicBrainzService.normalizeTitle;

/**
 * Book and author metadata enrichment via Open Library (no API key). Modeled on MusicBrainzService:
 * search by title/name, verify the match on a normalized title, then fetch the details. Local sources
 * (epub OPF, nfo, cover.jpg) are primary; this only fills gaps.
 */
@Slf4j
@Component
public class OpenLibraryService {

    private static final String COVERS_BASE = "https://covers.openlibrary.org/b/id/";
    private static final String AUTHOR_PHOTO_BASE = "https://covers.openlibrary.org/a/olid/";
    /** Language tag of the provider's own free-text prose (Open Library bios are English). */
    private static final String ENGLISH = "en";
    /** Open Library birth dates are free text ("5 September 1929", "1929-09-05", "c. 1929"). */
    private static final Pattern YEAR = Pattern.compile("\\d{4}");

    private final String openLibraryBase;
    private final WikipediaService wikipediaService;
    private final RestClient restClient;

    public OpenLibraryService(
            @Value("${app.ister.worker.openlibrary.base:https://openlibrary.org}") String openLibraryBase,
            WikipediaService wikipediaService) {
        this.openLibraryBase = openLibraryBase;
        this.wikipediaService = wikipediaService;
        this.restClient = MetadataRestClients.json();
    }

    /**
     * @param description the work description, or null
     * @param coverUrl    a large cover image URL, or null
     * @param firstPublishYear the first publication year across all editions of the work — the
     *                         original year, also for a translated edition — or 0 when unknown
     * @param workKey     the Open Library work key (e.g. "/works/OL45804W"), or null
     */
    public record BookInfo(String description, String coverUrl, int firstPublishYear, String workKey) {}

    /**
     * @param bios      biography per requested language tag; may be empty
     * @param photoUrl  a portrait URL, or null
     * @param birthYear the year of birth, or null when unknown
     * @param sourceKey the Open Library author key (e.g. "OL23919A")
     */
    public record AuthorInfo(Map<String, String> bios, String photoUrl, Integer birthYear, String sourceKey) {}

    /**
     * ISBN-first: an ISBN search hit is authoritative (no title verification — a Dutch edition's
     * ISBN rolls up to the original work, whose English title would never fuzzy-match the local
     * one). Falls back to the title+author search with normalized-title verification.
     */
    public Optional<BookInfo> getBookInfo(String title, String authorName, List<String> isbns) {
        Optional<Map<String, Object>> doc = isbns.stream()
                .map(this::searchWorkByIsbn)
                .flatMap(Optional::stream)
                .findFirst();
        if (doc.isEmpty()) {
            doc = searchWork(title, authorName);
        }
        if (doc.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> work = doc.get();
        String coverUrl = work.get("cover_i") instanceof Number coverId
                ? COVERS_BASE + coverId.longValue() + "-L.jpg"
                : null;
        int firstPublishYear = work.get("first_publish_year") instanceof Number year ? year.intValue() : 0;
        String workKey = work.get("key") instanceof String key ? key : null;
        String description = workKey != null ? fetchDescription(workKey) : null;
        if (description == null && coverUrl == null && firstPublishYear == 0) {
            return Optional.empty();
        }
        return Optional.of(new BookInfo(description, coverUrl, firstPublishYear, workKey));
    }

    /** The work an edition with this ISBN belongs to, or empty when Open Library doesn't know it. */
    private Optional<Map<String, Object>> searchWorkByIsbn(String isbn) {
        try {
            Thread.sleep(1000);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(openLibraryBase + "/search.json?q=isbn:{isbn}&limit=1&fields=key,title,cover_i,first_publish_year",
                            isbn)
                    .retrieve()
                    .body(Map.class);
            if (response != null && response.get("docs") instanceof List<?> docs && !docs.isEmpty()
                    && docs.getFirst() instanceof Map<?, ?> docMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> match = (Map<String, Object>) docMap;
                return Optional.of(match);
            }
            log.debug("No Open Library work for isbn={}", isbn);
            return Optional.empty();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Open Library isbn search error for isbn={}: {}", isbn, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Author bio, portrait and birth year. Open Library confirms the author exists and gives the
     * birth year, but its records are sparse — most carry no bio, photo or wikidata link at all — so
     * the biography and portrait come from Wikipedia, found by wikidata link when there is one and by
     * name otherwise.
     */
    public Optional<AuthorInfo> getAuthorInfo(String name, List<String> languageTags) {
        Optional<String> key = searchAuthorKey(name);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        String authorKey = key.get();
        Map<String, Object> author = fetchAuthor(authorKey);
        if (author.isEmpty()) {
            return Optional.empty();
        }

        String wikidataId = wikidataId(author);
        WikipediaService.Content wiki = wikidataId != null
                ? wikipediaService.fetchContent(wikidataId, languageTags)
                : wikipediaService.fetchContentForPerson(name, languageTags);
        Map<String, String> bios = new LinkedHashMap<>(wiki.bios());
        String openLibraryBio = textValue(author.get("bio"));
        // Open Library bios are English prose, so they can only fill the English slot — filing one
        // under whatever language happens to be configured first would show English text to a user
        // who asked for a Dutch biography. A deployment without English simply gets no fallback.
        if (openLibraryBio != null && languageTags != null && languageTags.contains(ENGLISH)) {
            bios.putIfAbsent(ENGLISH, openLibraryBio);
        }

        String photoUrl = hasPhoto(author) ? AUTHOR_PHOTO_BASE + authorKey + "-L.jpg" : wiki.thumbnail();
        Integer birthYear = parseYear(author.get("birth_date"));

        if (bios.isEmpty() && photoUrl == null && birthYear == null) {
            return Optional.empty();
        }
        return Optional.of(new AuthorInfo(bios, photoUrl, birthYear, authorKey));
    }

    /** Key ("OL23919A") of the first author search result whose name matches ours after normalisation. */
    private Optional<String> searchAuthorKey(String name) {
        try {
            Thread.sleep(1000);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(openLibraryBase + "/search/authors.json?q={name}&limit=5", name)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !(response.get("docs") instanceof List<?> docs)) {
                return Optional.empty();
            }
            String wanted = normalizeTitle(name);
            for (Object doc : docs) {
                if (doc instanceof Map<?, ?> docMap && matchesName(docMap, wanted)
                        && docMap.get("key") instanceof String key) {
                    return Optional.of(key);
                }
            }
            log.debug("No Open Library author matched name={}", name);
            return Optional.empty();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Open Library author search error for name={}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    /** Matches on the canonical name or any alternate spelling ("J.R.R. Tolkien" vs "J. R. R. Tolkien"). */
    private boolean matchesName(Map<?, ?> doc, String wanted) {
        if (doc.get("name") instanceof String name && namesMatch(wanted, name)) {
            return true;
        }
        return doc.get("alternate_names") instanceof List<?> alternates
                && alternates.stream().anyMatch(alt -> alt instanceof String s && namesMatch(wanted, s));
    }

    /** The author record ("/authors/OL...A"), or an empty map when the fetch failed. */
    private Map<String, Object> fetchAuthor(String authorKey) {
        try {
            Thread.sleep(1000);
            @SuppressWarnings("unchecked")
            Map<String, Object> author = restClient.get()
                    .uri(openLibraryBase + "/authors/{key}.json", authorKey)
                    .retrieve()
                    .body(Map.class);
            return author == null ? Map.of() : author;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (RestClientException e) {
            log.warn("Open Library author fetch error for {}: {}", authorKey, e.getMessage());
            return Map.of();
        }
    }

    /** Wikidata entity id from the author's remote_ids, or null. */
    private String wikidataId(Map<String, Object> author) {
        if (author.get("remote_ids") instanceof Map<?, ?> remoteIds
                && remoteIds.get("wikidata") instanceof String id && id.startsWith("Q")) {
            return id;
        }
        return null;
    }

    /** Open Library lists photo ids; a negative id means "no photo". */
    private boolean hasPhoto(Map<String, Object> author) {
        return author.get("photos") instanceof List<?> photos
                && photos.stream().anyMatch(p -> p instanceof Number n && n.longValue() > 0);
    }

    private Integer parseYear(Object birthDate) {
        if (!(birthDate instanceof String date)) {
            return null;
        }
        Matcher matcher = YEAR.matcher(date);
        return matcher.find() ? Integer.valueOf(matcher.group()) : null;
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
                        && namesMatch(wanted, (String) docMap.get("title"))) {
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

    /** The work's description ("/works/OL...W"). */
    private String fetchDescription(String workKey) {
        try {
            Thread.sleep(1000);
            @SuppressWarnings("unchecked")
            Map<String, Object> work = restClient.get()
                    .uri(openLibraryBase + workKey + ".json")
                    .retrieve()
                    .body(Map.class);
            return work == null ? null : textValue(work.get("description"));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RestClientException e) {
            log.warn("Open Library work fetch error for {}: {}", workKey, e.getMessage());
            return null;
        }
    }

    /** Open Library free text (description, bio) is either a plain string or a {type,value} object. */
    private String textValue(Object field) {
        if (field instanceof String s && !s.isBlank()) {
            return s.strip();
        }
        if (field instanceof Map<?, ?> map && map.get("value") instanceof String value && !value.isBlank()) {
            return value.strip();
        }
        return null;
    }
}
