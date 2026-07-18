package app.ister.worker.events.wikipedia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikidataBookSeriesServiceTest {

    private static final List<String> TAGS = List.of("en", "nl");

    @InjectMocks
    private WikidataBookSeriesService subject;

    @Mock
    private WikipediaService wikipediaService;

    /** A Wikidata entity document as Special:EntityData returns it. */
    private Map<String, Object> entity(String id, Map<String, Object> labels, Map<String, Object> claims) {
        return Map.of("entities", Map.of(id, Map.of("labels", labels, "claims", claims)));
    }

    private Map<String, Object> label(String language, String value) {
        return Map.of(language, Map.of("language", language, "value", value));
    }

    private Map<String, Object> partOfSeriesClaim(String seriesId, String ordinal) {
        Map<String, Object> statement = ordinal == null
                ? Map.of("mainsnak", mainsnak(seriesId))
                : Map.of("mainsnak", mainsnak(seriesId),
                        "qualifiers", Map.of("P1545", List.of(
                                Map.of("datavalue", Map.of("value", ordinal)))));
        return Map.of("P179", List.of(statement));
    }

    private Map<String, Object> mainsnak(String itemId) {
        return Map.of("datavalue", Map.of("value", Map.of("id", itemId)));
    }

    private Map<String, Object> publicationDates(String... times) {
        return Map.of("P577", List.of(times).stream()
                .map(time -> (Object) Map.of("mainsnak",
                        Map.of("datavalue", Map.of("value", Map.of("time", time)))))
                .toList());
    }

    private void givenEntityFieldsPassThrough() {
        lenient().when(wikipediaService.entityField(any(), anyString(), anyString())).thenAnswer(invocation -> {
            Map<String, Object> wikidata = invocation.getArgument(0);
            String id = invocation.getArgument(1);
            String field = invocation.getArgument(2);
            return wikidata.get("entities") instanceof Map<?, ?> entities
                    && entities.get(id) instanceof Map<?, ?> entityMap ? entityMap.get(field) : null;
        });
    }

    @Test
    void findsOrdinalAndEarliestPublicationYear() {
        givenEntityFieldsPassThrough();
        Map<String, Object> claims = new java.util.HashMap<>(partOfSeriesClaim("Q2195491", "7"));
        claims.putAll(publicationDates("+2011-05-01T00:00:00Z", "+2007-11-01T00:00:00Z"));
        Map<String, Object> bookEntity = entity("Q3497559", label("en", "Erak's Ransom"), claims);
        Map<String, Object> seriesEntity = entity("Q2195491", label("nl", "De Grijze Jager"), Map.of());

        when(wikipediaService.searchEntityIds("Losgeld voor Erak", "en")).thenReturn(List.of("Q3497559"));
        when(wikipediaService.fetchWikidataEntity("Q3497559")).thenReturn(bookEntity);
        when(wikipediaService.fetchWikidataEntity("Q2195491")).thenReturn(seriesEntity);
        when(wikipediaService.labelMatches(any(), anyString(), anyString(), any())).thenReturn(true);

        Optional<WikidataBookSeriesService.BookSeriesInfo> info =
                subject.findBookInSeries("Losgeld voor Erak", "De Grijze Jager", TAGS);

        assertEquals("Q3497559", info.orElseThrow().wikidataId());
        assertEquals(7.0, info.orElseThrow().seriesIndex());
        assertEquals(2007, info.orElseThrow().firstPublicationYear());
    }

    /** A same-titled item in another series (the film, the game) must not slip through. */
    @Test
    void rejectsCandidateWhosePartOfPointsAtAnotherSeries() {
        givenEntityFieldsPassThrough();
        Map<String, Object> bookEntity = entity("Q999",
                label("en", "Losgeld voor Erak"), partOfSeriesClaim("Q555", "3"));
        Map<String, Object> otherSeries = entity("Q555", label("en", "Some Other Series"), Map.of());

        when(wikipediaService.searchEntityIds(anyString(), anyString())).thenReturn(List.of("Q999"));
        when(wikipediaService.fetchWikidataEntity("Q999")).thenReturn(bookEntity);
        when(wikipediaService.fetchWikidataEntity("Q555")).thenReturn(otherSeries);
        // The book label matches, the series label does not.
        when(wikipediaService.labelMatches(bookEntity, "Q999", "losgeldvoorerak", TAGS)).thenReturn(true);
        when(wikipediaService.labelMatches(otherSeries, "Q555", "degrijzejager", TAGS)).thenReturn(false);

        assertTrue(subject.findBookInSeries("Losgeld voor Erak", "De Grijze Jager", TAGS).isEmpty());
    }

    /** Membership without an ordinal still yields the year — series position stays unknown. */
    @Test
    void confirmsMembershipWithoutAnOrdinal() {
        givenEntityFieldsPassThrough();
        Map<String, Object> claims = new java.util.HashMap<>(partOfSeriesClaim("Q2195491", null));
        claims.putAll(publicationDates("+2017-01-01T00:00:00Z"));
        Map<String, Object> bookEntity = entity("Q1", label("nl", "De jacht op het schaduwdier"), claims);

        when(wikipediaService.searchEntityIds(anyString(), anyString())).thenReturn(List.of("Q1"));
        when(wikipediaService.fetchWikidataEntity("Q1")).thenReturn(bookEntity);
        when(wikipediaService.fetchWikidataEntity("Q2195491")).thenReturn(Map.of());
        when(wikipediaService.labelMatches(any(), anyString(), anyString(), any())).thenReturn(true);

        Optional<WikidataBookSeriesService.BookSeriesInfo> info =
                subject.findBookInSeries("De jacht op het schaduwdier", "De Grijze Jager", TAGS);

        assertEquals(null, info.orElseThrow().seriesIndex());
        assertEquals(2017, info.orElseThrow().firstPublicationYear());
    }

    @Test
    void skipsCandidatesWhoseLabelDoesNotMatch() {
        when(wikipediaService.searchEntityIds(anyString(), anyString())).thenReturn(List.of("Q1"));
        when(wikipediaService.fetchWikidataEntity("Q1")).thenReturn(Map.of());
        when(wikipediaService.labelMatches(any(), anyString(), anyString(), any())).thenReturn(false);

        assertTrue(subject.findBookInSeries("Some Book", "Some Series", TAGS).isEmpty());
        verify(wikipediaService, never()).entityField(any(), anyString(), anyString());
    }

    @Test
    void returnsEmptyOnBlankInput() {
        assertTrue(subject.findBookInSeries(null, "Series", TAGS).isEmpty());
        assertTrue(subject.findBookInSeries("Book", " ", TAGS).isEmpty());
        assertTrue(subject.findBookInSeries("Book", "Series", List.of()).isEmpty());
    }

    // ===== discoverSeries =====

    private Map<String, Object> authorClaim(String authorId) {
        return Map.of("P50", List.of(Map.of("mainsnak", mainsnak(authorId))));
    }

    private Map<String, Object> bookClaims(String seriesId, String ordinal, String authorId) {
        Map<String, Object> claims = new java.util.HashMap<>(partOfSeriesClaim(seriesId, ordinal));
        if (authorId != null) {
            claims.putAll(authorClaim(authorId));
        }
        claims.putAll(publicationDates("+2003-06-21T00:00:00Z"));
        return claims;
    }

    @Test
    void discoversWhichOfTheAuthorsSeriesTheBookBelongsTo() {
        givenEntityFieldsPassThrough();
        Map<String, Object> bookEntity = entity("Q102225",
                label("nl", "Harry Potter en de Orde van de Feniks"),
                bookClaims("Q8337", "5", "Q34660"));
        Map<String, Object> seriesEntity = entity("Q8337", label("en", "Harry Potter"), Map.of());
        Map<String, Object> authorEntity = entity("Q34660", label("en", "J. K. Rowling"), Map.of());

        when(wikipediaService.searchEntityIds("Harry Potter en de Orde van de Feniks", "en"))
                .thenReturn(List.of("Q102225"));
        when(wikipediaService.fetchWikidataEntity("Q102225")).thenReturn(bookEntity);
        when(wikipediaService.fetchWikidataEntity("Q8337")).thenReturn(seriesEntity);
        when(wikipediaService.fetchWikidataEntity("Q34660")).thenReturn(authorEntity);
        when(wikipediaService.labelMatches(bookEntity, "Q102225", "harrypotterendeordevandefeniks", TAGS))
                .thenReturn(true);
        when(wikipediaService.labelMatches(seriesEntity, "Q8337", "degrijzejager", TAGS)).thenReturn(false);
        when(wikipediaService.labelMatches(seriesEntity, "Q8337", "harrypotter", TAGS)).thenReturn(true);
        when(wikipediaService.labelMatches(authorEntity, "Q34660", "jkrowling", TAGS)).thenReturn(true);

        Optional<WikidataBookSeriesService.DiscoveredSeries> found = subject.discoverSeries(
                "Harry Potter en de Orde van de Feniks", "J.K. Rowling",
                List.of("De Grijze Jager", "Harry Potter"), TAGS);

        assertEquals("Q102225", found.orElseThrow().wikidataId());
        assertEquals("Harry Potter", found.orElseThrow().seriesName());
        assertEquals(5.0, found.orElseThrow().seriesIndex());
        assertEquals(2003, found.orElseThrow().firstPublicationYear());
    }

    /** The series matches, but the item's P50 is another writer: the match is rejected. */
    @Test
    void discoveryRejectsAnItemWhoseAuthorDoesNotMatch() {
        givenEntityFieldsPassThrough();
        Map<String, Object> bookEntity = entity("Q1",
                label("en", "Some Book"), bookClaims("Q2", "1", "Q3"));
        Map<String, Object> seriesEntity = entity("Q2", label("en", "Some Series"), Map.of());
        Map<String, Object> otherAuthor = entity("Q3", label("en", "Somebody Else"), Map.of());

        when(wikipediaService.searchEntityIds(anyString(), anyString())).thenReturn(List.of("Q1"));
        when(wikipediaService.fetchWikidataEntity("Q1")).thenReturn(bookEntity);
        when(wikipediaService.fetchWikidataEntity("Q2")).thenReturn(seriesEntity);
        when(wikipediaService.fetchWikidataEntity("Q3")).thenReturn(otherAuthor);
        when(wikipediaService.labelMatches(bookEntity, "Q1", "somebook", TAGS)).thenReturn(true);
        when(wikipediaService.labelMatches(seriesEntity, "Q2", "someseries", TAGS)).thenReturn(true);
        // The author label does not match (unstubbed → false).

        assertTrue(subject.discoverSeries("Some Book", "The Author", List.of("Some Series"), TAGS)
                .isEmpty());
    }

    /** An item without any P50 statement is never trusted — the film has no author either. */
    @Test
    void discoveryRejectsAnItemWithoutAnAuthorStatement() {
        givenEntityFieldsPassThrough();
        Map<String, Object> bookEntity = entity("Q1",
                label("en", "Some Book"), bookClaims("Q2", "1", null));
        Map<String, Object> seriesEntity = entity("Q2", label("en", "Some Series"), Map.of());

        when(wikipediaService.searchEntityIds(anyString(), anyString())).thenReturn(List.of("Q1"));
        when(wikipediaService.fetchWikidataEntity("Q1")).thenReturn(bookEntity);
        when(wikipediaService.fetchWikidataEntity("Q2")).thenReturn(seriesEntity);
        when(wikipediaService.labelMatches(bookEntity, "Q1", "somebook", TAGS)).thenReturn(true);
        when(wikipediaService.labelMatches(seriesEntity, "Q2", "someseries", TAGS)).thenReturn(true);

        assertTrue(subject.discoverSeries("Some Book", "The Author", List.of("Some Series"), TAGS)
                .isEmpty());
    }

    /** Series check runs before the author check: no candidate series match, no author fetch. */
    @Test
    void discoveryRejectsWhenTheSeriesIsNotAmongTheCandidates() {
        givenEntityFieldsPassThrough();
        Map<String, Object> bookEntity = entity("Q1",
                label("en", "Some Book"), bookClaims("Q2", "1", "Q3"));
        Map<String, Object> seriesEntity = entity("Q2", label("en", "Unrelated Series"), Map.of());

        when(wikipediaService.searchEntityIds(anyString(), anyString())).thenReturn(List.of("Q1"));
        when(wikipediaService.fetchWikidataEntity("Q1")).thenReturn(bookEntity);
        when(wikipediaService.fetchWikidataEntity("Q2")).thenReturn(seriesEntity);
        when(wikipediaService.labelMatches(bookEntity, "Q1", "somebook", TAGS)).thenReturn(true);
        // The series label matches no candidate (unstubbed → false).

        assertTrue(subject.discoverSeries("Some Book", "The Author", List.of("Some Series"), TAGS)
                .isEmpty());
        verify(wikipediaService, never()).fetchWikidataEntity("Q3");
    }

    @Test
    void discoveryReturnsEmptyOnBlankInput() {
        assertTrue(subject.discoverSeries(null, "Author", List.of("Series"), TAGS).isEmpty());
        assertTrue(subject.discoverSeries("Book", " ", List.of("Series"), TAGS).isEmpty());
        assertTrue(subject.discoverSeries("Book", "Author", List.of(), TAGS).isEmpty());
        assertTrue(subject.discoverSeries("Book", "Author", List.of("Series"), List.of()).isEmpty());
    }
}
