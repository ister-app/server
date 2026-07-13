package app.ister.worker.events.wikipedia;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static app.ister.worker.events.musicbrainz.MusicBrainzService.normalizeTitle;

/**
 * Per-language biographies (and a portrait fallback) for a person, keyed on their Wikidata entity:
 * the entity's {@code <tag>wiki} sitelink gives the page title, the Wikipedia REST summary gives the
 * extract and thumbnail. Shared by MusicBrainz (music artists) and Open Library (book authors), which
 * both know a person's Wikidata id but nothing about their biography.
 */
@Slf4j
@Component
public class WikipediaService {

    private static final String USER_AGENT = "IsterServer/1.0 (info@ister.app)";
    /** Wikidata id of "human", the P31 (instance of) value every person carries. */
    private static final String HUMAN = "Q5";
    private static final int MAX_CANDIDATES = 3;

    /** Per-language bios plus the first available thumbnail (image fallback). Either may be empty/null. */
    public record Content(Map<String, String> bios, String thumbnail) {
        public static final Content EMPTY = new Content(Map.of(), null);
    }

    private record Summary(String extract, String thumbnail) {}

    private final String wikidataEntityBase;
    private final String wikidataApiBase;
    private final RestClient restClient;

    public WikipediaService(
            @Value("${app.ister.worker.wikidata.entity-base:https://www.wikidata.org/wiki/Special:EntityData/}")
            String wikidataEntityBase,
            @Value("${app.ister.worker.wikidata.api-base:https://www.wikidata.org/w/api.php}")
            String wikidataApiBase) {
        this.wikidataEntityBase = wikidataEntityBase;
        this.wikidataApiBase = wikidataApiBase;
        this.restClient = RestClient.builder()
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /** Fetches the Wikidata entity once and reads one Wikipedia summary per requested language tag. */
    public Content fetchContent(String wikidataId, List<String> languageTags) {
        if (wikidataId == null || languageTags == null || languageTags.isEmpty()) {
            return Content.EMPTY;
        }
        return contentOf(fetchWikidataEntity(wikidataId), wikidataId, languageTags);
    }

    /**
     * Same, for a person we only know by name: Wikidata is searched and the first human whose label
     * matches the name is used. Open Library author records rarely carry a wikidata id, so without
     * this most authors would get no biography at all.
     */
    public Content fetchContentForPerson(String name, List<String> languageTags) {
        if (name == null || languageTags == null || languageTags.isEmpty()) {
            return Content.EMPTY;
        }
        String wanted = normalizeTitle(name);
        for (String candidateId : searchEntityIds(name, languageTags.getFirst())) {
            Map<String, Object> entity = fetchWikidataEntity(candidateId);
            if (isHuman(entity, candidateId) && labelMatches(entity, candidateId, wanted, languageTags)) {
                return contentOf(entity, candidateId, languageTags);
            }
        }
        log.debug("No matching Wikidata person found for name={}", name);
        return Content.EMPTY;
    }

    private Content contentOf(Map<String, Object> wikidata, String wikidataId, List<String> languageTags) {
        Map<String, String> bios = new LinkedHashMap<>();
        String thumbnail = null;
        for (String tag : languageTags) {
            String title = sitelinkTitle(wikidata, wikidataId, tag);
            Summary summary = title == null ? null : fetchSummary(tag, title);
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
        return new Content(bios, thumbnail);
    }

    /** Ids of the top Wikidata items for a name, best match first. */
    private List<String> searchEntityIds(String name, String languageTag) {
        try {
            Thread.sleep(500);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(wikidataApiBase + "?action=wbsearchentities&format=json&type=item&limit={limit}"
                            + "&language={lang}&uselang={uselang}&search={name}",
                            MAX_CANDIDATES, languageTag, languageTag, name)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !(response.get("search") instanceof List<?> results)) {
                return List.of();
            }
            return results.stream()
                    .filter(Map.class::isInstance)
                    .map(r -> ((Map<?, ?>) r).get("id"))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (RestClientException e) {
            log.debug("Wikidata search failed for {}: {}", name, e.getMessage());
            return List.of();
        }
    }

    /** True when the entity is an instance of (P31) human (Q5) — a name search also returns books,
     * films and bands named after the person. */
    private boolean isHuman(Map<String, Object> wikidata, String wikidataId) {
        Object claims = entityField(wikidata, wikidataId, "claims");
        if (!(claims instanceof Map<?, ?> claimMap) || !(claimMap.get("P31") instanceof List<?> instanceOf)) {
            return false;
        }
        return instanceOf.stream().anyMatch(claim -> claim instanceof Map<?, ?> c
                && c.get("mainsnak") instanceof Map<?, ?> snak
                && snak.get("datavalue") instanceof Map<?, ?> value
                && value.get("value") instanceof Map<?, ?> item
                && HUMAN.equals(item.get("id")));
    }

    /** Guards against a same-named different person: the entity's label in one of our languages must
     * equal the name we searched for. */
    private boolean labelMatches(Map<String, Object> wikidata, String wikidataId, String wantedName,
                                 List<String> languageTags) {
        if (!(entityField(wikidata, wikidataId, "labels") instanceof Map<?, ?> labels)) {
            return false;
        }
        return languageTags.stream().anyMatch(tag -> labels.get(tag) instanceof Map<?, ?> label
                && label.get("value") instanceof String value
                && wantedName.equals(normalizeTitle(value)));
    }

    private Object entityField(Map<String, Object> wikidata, String wikidataId, String field) {
        if (wikidata.get("entities") instanceof Map<?, ?> entities
                && entities.get(wikidataId) instanceof Map<?, ?> entity) {
            return entity.get(field);
        }
        return null;
    }

    /** Wikidata entity document, or an empty map when the fetch failed or the body was null. */
    private Map<String, Object> fetchWikidataEntity(String wikidataId) {
        try {
            Thread.sleep(500);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.get()
                    .uri(wikidataEntityBase + "{id}.json", wikidataId)
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
    private String sitelinkTitle(Map<String, Object> wikidata, String wikidataId, String languageTag) {
        if (!(entityField(wikidata, wikidataId, "sitelinks") instanceof Map<?, ?> sitelinks)) {
            return null;
        }
        if (sitelinks.get(languageTag + "wiki") instanceof Map<?, ?> link
                && link.get("title") instanceof String title) {
            return title;
        }
        return null;
    }

    private Summary fetchSummary(String languageTag, String pageTitle) {
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
            return new Summary(extractText, thumbnail);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RestClientException e) {
            log.debug("Wikipedia summary fetch failed for {}/{}: {}", languageTag, pageTitle, e.getMessage());
            return null;
        }
    }
}
