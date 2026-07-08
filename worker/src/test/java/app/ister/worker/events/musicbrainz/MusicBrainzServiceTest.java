package app.ister.worker.events.musicbrainz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MusicBrainzServiceTest {

    private static final String RELEASE_ENDPOINT = "https://musicbrainz.org/ws/2/release?query=";
    private static final String ARTIST_ENDPOINT = "https://musicbrainz.org/ws/2/artist?query=";
    private static final String ARTIST_LOOKUP_ENDPOINT = "https://musicbrainz.org/ws/2/artist/";
    private static final String RELEASE_GROUP_ENDPOINT = "https://musicbrainz.org/ws/2/release-group?query=";
    private static final String WIKIPEDIA_SUMMARY_ENDPOINT = "https://en.wikipedia.org/api/rest_v1/page/summary/";
    private static final String WIKIPEDIA_NL_SUMMARY_ENDPOINT = "https://nl.wikipedia.org/api/rest_v1/page/summary/";
    private static final String WIKIDATA_ENDPOINT = "https://www.wikidata.org/wiki/Special:EntityData/";
    private static final List<String> EN = List.of("en");
    private static final List<String> EN_NL = List.of("en", "nl");

    private MusicBrainzService subject;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        subject = new MusicBrainzService();
        // The service builds its own RestClient in the constructor; rebind it to a mock server
        // so no real network calls are made.
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(subject, "restClient", builder.build());
    }

    // ========== getCoverArtUrl ==========

    @Test
    void getCoverArtUrlReturnsReleaseGroupFrontWhenReleaseGroupFound() {
        server.expect(requestTo(startsWith(RELEASE_GROUP_ENDPOINT)))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"release-groups\":[{\"id\":\"rg-1\",\"title\":\"OK Computer\"}]}", MediaType.APPLICATION_JSON));

        Optional<String> result = subject.getCoverArtUrl("Radiohead", "OK Computer");

        assertEquals(Optional.of("https://coverartarchive.org/release-group/rg-1/front"), result);
        server.verify();
    }

    @Test
    void getCoverArtUrlMatchesStylizedCanonicalTitle() {
        // Local tag "Emotion" must still match MusicBrainz's stylized "E•MO•TION".
        server.expect(requestTo(startsWith(RELEASE_GROUP_ENDPOINT)))
                .andRespond(withSuccess("{\"release-groups\":[{\"id\":\"rg-emo\",\"title\":\"E\\u2022MO\\u2022TION\"}]}", MediaType.APPLICATION_JSON));

        Optional<String> result = subject.getCoverArtUrl("Carly Rae Jepsen", "Emotion");

        assertEquals(Optional.of("https://coverartarchive.org/release-group/rg-emo/front"), result);
        server.verify();
    }

    @Test
    void getCoverArtUrlSkipsResultsWhoseTitleDoesNotMatch() {
        // A loosely-scored unrelated result must not be accepted; falls through to an empty release query.
        server.expect(requestTo(startsWith(RELEASE_GROUP_ENDPOINT)))
                .andRespond(withSuccess("{\"release-groups\":[{\"id\":\"rg-x\",\"title\":\"Something Completely Different\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(RELEASE_ENDPOINT)))
                .andRespond(withSuccess("{\"releases\":[]}", MediaType.APPLICATION_JSON));

        assertEquals(Optional.empty(), subject.getCoverArtUrl("Radiohead", "OK Computer"));
        server.verify();
    }

    @Test
    void getCoverArtUrlPicksTheTitleMatchAmongSeveralResults() {
        server.expect(requestTo(startsWith(RELEASE_GROUP_ENDPOINT)))
                .andRespond(withSuccess("{\"release-groups\":[" +
                        "{\"id\":\"rg-wrong\",\"title\":\"21st Century Digital Girl\"}," +
                        "{\"id\":\"rg-right\",\"title\":\"21st Century\"}]}", MediaType.APPLICATION_JSON));

        Optional<String> result = subject.getCoverArtUrl("Groove Coverage", "21st Century");

        assertEquals(Optional.of("https://coverartarchive.org/release-group/rg-right/front"), result);
        server.verify();
    }

    @Test
    void getCoverArtUrlFallsBackToReleaseWhenNoReleaseGroup() {
        server.expect(requestTo(startsWith(RELEASE_GROUP_ENDPOINT)))
                .andRespond(withSuccess("{\"release-groups\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(RELEASE_ENDPOINT)))
                .andRespond(withSuccess("{\"releases\":[{\"id\":\"abc-123\",\"title\":\"OK Computer\"}]}", MediaType.APPLICATION_JSON));

        Optional<String> result = subject.getCoverArtUrl("Radiohead", "OK Computer");

        assertEquals(Optional.of("https://coverartarchive.org/release/abc-123/front"), result);
        server.verify();
    }

    @Test
    void getCoverArtUrlQuotesAndEscapesSearchTerms() {
        // Quotes in the artist name must be escaped inside the quoted query term
        server.expect(requestTo(containsString("%5C%22Best%5C%22")))
                .andRespond(withSuccess("{\"release-groups\":[{\"id\":\"rg-1\",\"title\":\"Album\"}]}", MediaType.APPLICATION_JSON));

        Optional<String> result = subject.getCoverArtUrl("The \"Best\" Band", "Album");

        assertTrue(result.isPresent());
        server.verify();
    }

    @Test
    void getCoverArtUrlReturnsEmptyWhenNoReleaseGroupOrRelease() {
        server.expect(requestTo(startsWith(RELEASE_GROUP_ENDPOINT)))
                .andRespond(withSuccess("{\"release-groups\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(RELEASE_ENDPOINT)))
                .andRespond(withSuccess("{\"releases\":[]}", MediaType.APPLICATION_JSON));

        assertEquals(Optional.empty(), subject.getCoverArtUrl("Unknown", "Nothing"));
    }

    @Test
    void getCoverArtUrlReturnsEmptyOnServerError() {
        server.expect(requestTo(startsWith(RELEASE_GROUP_ENDPOINT)))
                .andRespond(withServerError());
        server.expect(requestTo(startsWith(RELEASE_ENDPOINT)))
                .andRespond(withServerError());

        assertEquals(Optional.empty(), subject.getCoverArtUrl("Radiohead", "OK Computer"));
    }

    @Test
    void normalizeAlbumNameStripsEditionAndFormatNoise() {
        assertEquals("Delta", MusicBrainzService.normalizeAlbumName("Delta (Deluxe Edition)"));
        assertEquals("Back to Bedlam", MusicBrainzService.normalizeAlbumName("Back to Bedlam (RE 2005)"));
        assertEquals("As I Am", MusicBrainzService.normalizeAlbumName("As I Am [Bonus Track]"));
        assertEquals("Whispers of the Forest", MusicBrainzService.normalizeAlbumName("Whispers of the Forest FLAC"));
    }

    @Test
    void normalizeTitleCollapsesStylizationAndPunctuation() {
        assertEquals(MusicBrainzService.normalizeTitle("Emotion"), MusicBrainzService.normalizeTitle("E•MO•TION"));
        assertEquals("21stcentury", MusicBrainzService.normalizeTitle("21st Century"));
        assertEquals("", MusicBrainzService.normalizeTitle(null));
    }

    @Test
    void normalizeAlbumNameKeepsOriginalWhenStrippingEmptiesIt() {
        assertEquals("(Deluxe)", MusicBrainzService.normalizeAlbumName("(Deluxe)"));
        assertEquals("", MusicBrainzService.normalizeAlbumName(null));
    }

    // ========== getArtistInfo ==========
    // Flow: search /artist?query= (MBID + type/life-span/tags) → lookup /artist/{mbid} (annotation +
    // image/wikidata relations) → Wikidata entity (sitelinks) → per-language Wikipedia summary
    // (bio extract + thumbnail fallback).

    @Test
    void getArtistInfoUsesImageRelationAndWikipediaBio() {
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withSuccess("{\"artists\":[{\"id\":\"mbid-1\",\"name\":\"Radiohead\",\"type\":\"Group\",\"tags\":[{\"name\":\"rock\"}]}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(ARTIST_LOOKUP_ENDPOINT)))
                .andRespond(withSuccess("""
                        {"relations":[
                          {"type":"image","url":{"resource":"https://commons.wikimedia.org/wiki/File:Radiohead.jpg"}},
                          {"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q1"}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(WIKIDATA_ENDPOINT)))
                .andRespond(withSuccess("{\"entities\":{\"Q1\":{\"sitelinks\":{\"enwiki\":{\"title\":\"Radiohead\"}}}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(WIKIPEDIA_SUMMARY_ENDPOINT + "Radiohead"))
                .andRespond(withSuccess("{\"extract\":\"An English rock band.\",\"thumbnail\":{\"source\":\"https://img.example/thumb.jpg\"}}",
                        MediaType.APPLICATION_JSON));

        Optional<MusicBrainzService.ArtistInfo> result = subject.getArtistInfo("Radiohead", EN);

        assertTrue(result.isPresent());
        assertEquals("rock", result.get().genre());
        assertEquals("An English rock band.", result.get().bios().get("en"));
        // The MusicBrainz image relation wins over the Wikipedia thumbnail.
        assertEquals("https://commons.wikimedia.org/wiki/Special:FilePath/Radiohead.jpg?width=1000",
                result.get().imageUrl());
        server.verify();
    }

    @Test
    void getArtistInfoFetchesBioPerConfiguredLanguageAndFallsBackToThumbnail() {
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withSuccess("{\"artists\":[{\"id\":\"mbid-1\",\"name\":\"Radiohead\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(ARTIST_LOOKUP_ENDPOINT)))
                .andRespond(withSuccess("{\"relations\":[{\"type\":\"wikidata\",\"url\":{\"resource\":\"https://www.wikidata.org/wiki/Q1\"}}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(WIKIDATA_ENDPOINT)))
                .andRespond(withSuccess("{\"entities\":{\"Q1\":{\"sitelinks\":{\"enwiki\":{\"title\":\"Radiohead\"},\"nlwiki\":{\"title\":\"Radiohead (band)\"}}}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(WIKIPEDIA_SUMMARY_ENDPOINT + "Radiohead"))
                .andRespond(withSuccess("{\"extract\":\"English bio\",\"thumbnail\":{\"source\":\"https://img.example/thumb.jpg\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(WIKIPEDIA_NL_SUMMARY_ENDPOINT)))
                .andRespond(withSuccess("{\"extract\":\"Nederlandse bio\"}", MediaType.APPLICATION_JSON));

        Optional<MusicBrainzService.ArtistInfo> result = subject.getArtistInfo("Radiohead", EN_NL);

        assertTrue(result.isPresent());
        assertEquals("English bio", result.get().bios().get("en"));
        assertEquals("Nederlandse bio", result.get().bios().get("nl"));
        // No image relation, so the first summary's thumbnail is used.
        assertEquals("https://img.example/thumb.jpg", result.get().imageUrl());
        server.verify();
    }

    @Test
    void getArtistInfoMatchesStylizedNameAndKeepsLifeSpan() {
        // Reproduces the Ariana Grande case: our "Ariana Grande" must match and yield birth year 1993.
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withSuccess("{\"artists\":[{\"id\":\"mbid-ag\",\"name\":\"Ariana Grande\",\"type\":\"Person\",\"life-span\":{\"begin\":\"1993-06-26\"},\"tags\":[{\"name\":\"pop\"}]}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(ARTIST_LOOKUP_ENDPOINT)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Optional<MusicBrainzService.ArtistInfo> result = subject.getArtistInfo("Ariana Grande", EN);

        assertTrue(result.isPresent());
        assertEquals("Person", result.get().type());
        assertEquals("1993-06-26", result.get().lifeSpanBegin());
        assertEquals("pop", result.get().genre());
    }

    @Test
    void getArtistInfoReturnsPresentEvenWithNoBioGenreOrImage() {
        // Found but bare: still returned so the life-span birth year can be stored (it links to a TMDB actor).
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withSuccess("{\"artists\":[{\"id\":\"mbid-1\",\"name\":\"Some Person\",\"type\":\"Person\",\"life-span\":{\"begin\":\"1980\"}}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(ARTIST_LOOKUP_ENDPOINT)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Optional<MusicBrainzService.ArtistInfo> result = subject.getArtistInfo("Some Person", EN);

        assertTrue(result.isPresent());
        assertTrue(result.get().bios().isEmpty());
        assertNull(result.get().genre());
        assertNull(result.get().imageUrl());
        assertEquals("1980", result.get().lifeSpanBegin());
    }

    @Test
    void getArtistInfoFallsBackToAnnotationWhenNoWikidata() {
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withSuccess("{\"artists\":[{\"id\":\"m\",\"name\":\"Radiohead\",\"tags\":[{\"name\":\"rock\"}]}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(ARTIST_LOOKUP_ENDPOINT)))
                .andRespond(withSuccess("{\"annotation\":{\"text\":\"MB annotation bio\"}}", MediaType.APPLICATION_JSON));

        Optional<MusicBrainzService.ArtistInfo> result = subject.getArtistInfo("Radiohead", EN);

        assertTrue(result.isPresent());
        assertEquals("MB annotation bio", result.get().bios().get("en"));
        assertNull(result.get().imageUrl());
        // No wikidata relation, so no Wikidata/Wikipedia calls were made.
        server.verify();
    }

    @Test
    void getArtistInfoContinuesWhenWikipediaFails() {
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withSuccess("{\"artists\":[{\"id\":\"m\",\"name\":\"Radiohead\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(ARTIST_LOOKUP_ENDPOINT)))
                .andRespond(withSuccess("{\"relations\":[{\"type\":\"wikidata\",\"url\":{\"resource\":\"https://www.wikidata.org/wiki/Q1\"}}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(WIKIDATA_ENDPOINT)))
                .andRespond(withSuccess("{\"entities\":{\"Q1\":{\"sitelinks\":{\"enwiki\":{\"title\":\"Radiohead\"}}}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(WIKIPEDIA_SUMMARY_ENDPOINT + "Radiohead"))
                .andRespond(withServerError());

        Optional<MusicBrainzService.ArtistInfo> result = subject.getArtistInfo("Radiohead", EN);

        assertTrue(result.isPresent());
        assertTrue(result.get().bios().isEmpty());
        assertNull(result.get().imageUrl());
    }

    @Test
    void getArtistInfoReturnsEmptyWhenNameDoesNotMatch() {
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withSuccess("{\"artists\":[{\"id\":\"mbid-x\",\"name\":\"A Completely Different Band\"}]}",
                        MediaType.APPLICATION_JSON));

        assertEquals(Optional.empty(), subject.getArtistInfo("Radiohead", EN));
        server.verify();
    }

    @Test
    void getArtistInfoReturnsEmptyWhenNoArtists() {
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withSuccess("{\"artists\":[]}", MediaType.APPLICATION_JSON));

        assertEquals(Optional.empty(), subject.getArtistInfo("Nobody", EN));
    }

    @Test
    void getArtistInfoReturnsEmptyOnServerError() {
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withServerError());

        assertEquals(Optional.empty(), subject.getArtistInfo("Radiohead", EN));
    }

    // ========== getAlbumInfo ==========

    @Test
    void getAlbumInfoReturnsDescriptionFromAnnotation() {
        server.expect(requestTo(startsWith(RELEASE_GROUP_ENDPOINT)))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"release-groups\":[{\"annotation\":{\"text\":\"Third studio album.\"}}]}",
                        MediaType.APPLICATION_JSON));

        Optional<MusicBrainzService.AlbumInfo> result = subject.getAlbumInfo("Radiohead", "OK Computer");

        assertTrue(result.isPresent());
        assertEquals("Third studio album.", result.get().description());
    }

    @Test
    void getAlbumInfoReturnsEmptyWhenAnnotationBlank() {
        server.expect(requestTo(startsWith(RELEASE_GROUP_ENDPOINT)))
                .andRespond(withSuccess("{\"release-groups\":[{\"annotation\":{\"text\":\"   \"}}]}",
                        MediaType.APPLICATION_JSON));

        assertEquals(Optional.empty(), subject.getAlbumInfo("Radiohead", "OK Computer"));
    }

    @Test
    void getAlbumInfoReturnsEmptyWhenNoReleaseGroups() {
        server.expect(requestTo(startsWith(RELEASE_GROUP_ENDPOINT)))
                .andRespond(withSuccess("{\"release-groups\":[]}", MediaType.APPLICATION_JSON));

        assertEquals(Optional.empty(), subject.getAlbumInfo("Unknown", "Nothing"));
    }

    @Test
    void getAlbumInfoReturnsEmptyOnServerError() {
        server.expect(requestTo(startsWith(RELEASE_GROUP_ENDPOINT)))
                .andRespond(withServerError());

        assertEquals(Optional.empty(), subject.getAlbumInfo("Radiohead", "OK Computer"));
    }
}
