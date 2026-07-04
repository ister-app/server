package app.ister.worker.events.musicbrainz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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
    private static final String RELEASE_GROUP_ENDPOINT = "https://musicbrainz.org/ws/2/release-group?query=";
    private static final String WIKIPEDIA_SUMMARY_ENDPOINT = "https://en.wikipedia.org/api/rest_v1/page/summary/";

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
    void getCoverArtUrlReturnsCoverArtArchiveUrlWhenReleaseFound() {
        server.expect(requestTo(startsWith(RELEASE_ENDPOINT)))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"releases\":[{\"id\":\"abc-123\"}]}", MediaType.APPLICATION_JSON));

        Optional<String> result = subject.getCoverArtUrl("Radiohead", "OK Computer");

        assertEquals(Optional.of("https://coverartarchive.org/release/abc-123/front"), result);
        server.verify();
    }

    @Test
    void getCoverArtUrlQuotesAndEscapesSearchTerms() {
        // Quotes in the artist name must be escaped inside the quoted query term
        server.expect(requestTo(containsString("%5C%22Best%5C%22")))
                .andRespond(withSuccess("{\"releases\":[{\"id\":\"abc-123\"}]}", MediaType.APPLICATION_JSON));

        Optional<String> result = subject.getCoverArtUrl("The \"Best\" Band", "Album");

        assertTrue(result.isPresent());
        server.verify();
    }

    @Test
    void getCoverArtUrlReturnsEmptyWhenNoReleases() {
        server.expect(requestTo(startsWith(RELEASE_ENDPOINT)))
                .andRespond(withSuccess("{\"releases\":[]}", MediaType.APPLICATION_JSON));

        assertEquals(Optional.empty(), subject.getCoverArtUrl("Unknown", "Nothing"));
    }

    @Test
    void getCoverArtUrlReturnsEmptyWhenReleaseHasNoId() {
        server.expect(requestTo(startsWith(RELEASE_ENDPOINT)))
                .andRespond(withSuccess("{\"releases\":[{\"title\":\"no id here\"}]}", MediaType.APPLICATION_JSON));

        assertEquals(Optional.empty(), subject.getCoverArtUrl("Radiohead", "OK Computer"));
    }

    @Test
    void getCoverArtUrlReturnsEmptyOnServerError() {
        server.expect(requestTo(startsWith(RELEASE_ENDPOINT)))
                .andRespond(withServerError());

        assertEquals(Optional.empty(), subject.getCoverArtUrl("Radiohead", "OK Computer"));
    }

    // ========== getArtistInfo ==========

    @Test
    void getArtistInfoReturnsBioGenreAndImageUrl() {
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withSuccess("""
                        {"artists":[{
                            "annotation":{"text":"An English rock band."},
                            "tags":[{"name":"rock"},{"name":"alternative"}],
                            "relations":[{"type":"wikipedia","url":{"resource":"https://en.wikipedia.org/wiki/Radiohead"}}]
                        }]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(WIKIPEDIA_SUMMARY_ENDPOINT + "Radiohead"))
                .andRespond(withSuccess("{\"thumbnail\":{\"source\":\"https://img.example/radiohead.jpg\"}}",
                        MediaType.APPLICATION_JSON));

        Optional<MusicBrainzService.ArtistInfo> result = subject.getArtistInfo("Radiohead");

        assertTrue(result.isPresent());
        assertEquals("An English rock band.", result.get().bio());
        assertEquals("rock", result.get().genre());
        assertEquals("https://img.example/radiohead.jpg", result.get().imageUrl());
        server.verify();
    }

    @Test
    void getArtistInfoExtractsTypeAndLifeSpanBegin() {
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withSuccess("""
                        {"artists":[{
                            "type":"Person",
                            "life-span":{"begin":"1946-05-31"},
                            "annotation":{"text":"A singer."}
                        }]}
                        """, MediaType.APPLICATION_JSON));

        Optional<MusicBrainzService.ArtistInfo> result = subject.getArtistInfo("Cher");

        assertTrue(result.isPresent());
        assertEquals("Person", result.get().type());
        assertEquals("1946-05-31", result.get().lifeSpanBegin());
    }

    @Test
    void getArtistInfoReturnsNullTypeAndLifeSpanWhenAbsent() {
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withSuccess("""
                        {"artists":[{"annotation":{"text":"A band."}}]}
                        """, MediaType.APPLICATION_JSON));

        Optional<MusicBrainzService.ArtistInfo> result = subject.getArtistInfo("Some Band");

        assertTrue(result.isPresent());
        assertNull(result.get().type());
        assertNull(result.get().lifeSpanBegin());
    }

    @Test
    void getArtistInfoReturnsEmptyWhenNoArtists() {
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withSuccess("{\"artists\":[]}", MediaType.APPLICATION_JSON));

        assertEquals(Optional.empty(), subject.getArtistInfo("Nobody"));
    }

    @Test
    void getArtistInfoReturnsEmptyWhenArtistHasNoUsableFields() {
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withSuccess("{\"artists\":[{\"name\":\"Radiohead\"}]}", MediaType.APPLICATION_JSON));

        assertEquals(Optional.empty(), subject.getArtistInfo("Radiohead"));
    }

    @Test
    void getArtistInfoReturnsPartialInfoWhenWikipediaFetchFails() {
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withSuccess("""
                        {"artists":[{
                            "annotation":{"text":"Bio text"},
                            "tags":[{"name":"rock"}],
                            "relations":[{"type":"wikipedia","url":{"resource":"https://en.wikipedia.org/wiki/Radiohead"}}]
                        }]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(WIKIPEDIA_SUMMARY_ENDPOINT + "Radiohead"))
                .andRespond(withServerError());

        Optional<MusicBrainzService.ArtistInfo> result = subject.getArtistInfo("Radiohead");

        assertTrue(result.isPresent());
        assertEquals("Bio text", result.get().bio());
        assertEquals("rock", result.get().genre());
        assertNull(result.get().imageUrl());
    }

    @Test
    void getArtistInfoIgnoresNonWikipediaRelations() {
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withSuccess("""
                        {"artists":[{
                            "annotation":{"text":"Bio text"},
                            "relations":[{"type":"discogs","url":{"resource":"https://www.discogs.com/artist/1"}}]
                        }]}
                        """, MediaType.APPLICATION_JSON));

        Optional<MusicBrainzService.ArtistInfo> result = subject.getArtistInfo("Radiohead");

        assertTrue(result.isPresent());
        assertEquals("Bio text", result.get().bio());
        assertNull(result.get().imageUrl());
        // No request to Wikipedia was made
        server.verify();
    }

    @Test
    void getArtistInfoReturnsEmptyOnServerError() {
        server.expect(requestTo(startsWith(ARTIST_ENDPOINT)))
                .andRespond(withServerError());

        assertEquals(Optional.empty(), subject.getArtistInfo("Radiohead"));
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
