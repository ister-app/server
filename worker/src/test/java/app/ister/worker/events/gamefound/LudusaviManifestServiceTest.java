package app.ister.worker.events.gamefound;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.games.GameSavePaths.SavePath;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.storage.FileAccess;
import app.ister.core.storage.LocalCacheStore;
import app.ister.core.storage.ObjectStoreRegistry;
import app.ister.worker.events.gamefound.LudusaviManifestService.Index;
import app.ister.worker.events.gamefound.LudusaviManifestService.Match;
import app.ister.worker.events.gamefound.LudusaviManifestService.MatchedBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

class LudusaviManifestServiceTest {

    private static final String MANIFEST_URL = "https://manifest.test/manifest.yaml";

    private static final String MANIFEST = """
            Stardew Valley:
              files:
                <winAppData>/StardewValley/Saves:
                  tags: [save]
                  when:
                    - os: windows
                <xdgConfig>/StardewValley/Saves:
                  tags: [save]
                  when:
                    - os: linux
                <winAppData>/StardewValley/startup_preferences:
                  tags: [config]
              steam:
                id: 413150
              cloud:
                steam: true
            Celeste™:
              files:
                <base>/Saves:
                  tags: [save]
              steam:
                id: 504230
            Celeste Classic:
              alias: Celeste™
            The Witness:
              files:
                <winAppData>/The Witness:
                  when:
                    - store: steam
            """;

    @TempDir
    Path cacheRoot;

    private DirectoryEntity cacheDir;
    private CacheDirectoryResolver resolver;
    private FileAccess fileAccess;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        cacheDir = DirectoryEntity.builder().id(UUID.randomUUID()).name("node-cache-directory").path(cacheRoot.toString()).build();
        resolver = mock(CacheDirectoryResolver.class);
        lenient().when(resolver.store()).thenReturn(new LocalCacheStore(cacheDir));
        fileAccess = new FileAccess(mock(ObjectStoreRegistry.class));
    }

    /** A service whose RestClient talks to the mock server instead of GitHub. */
    private LudusaviManifestService service(long refreshHours, boolean enabled) {
        LudusaviManifestService subject = new LudusaviManifestService(MANIFEST_URL, refreshHours, enabled, resolver, fileAccess);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(subject, "restClient", builder.build());
        return subject;
    }

    private void expectDownload(String body) {
        server.expect(once(), requestTo(MANIFEST_URL))
                .andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.TEXT_PLAIN));
    }

    private Path cachedManifest() {
        return cacheRoot.resolve(LudusaviManifestService.CACHE_KEY);
    }

    // ---- parse: the index ----

    @Test
    void indexesBySteamIdAndNormalizedTitle() {
        var index = LudusaviManifestService.parse(MANIFEST.getBytes(StandardCharsets.UTF_8));
        var stardew = index.bySteamId().get(413150L);
        assertEquals("Stardew Valley", stardew.name());
        assertTrue(stardew.steamCloud());
        assertEquals(List.of(
                new SavePath("<winAppData>/StardewValley/Saves", "windows"),
                new SavePath("<xdgConfig>/StardewValley/Saves", "linux")), stardew.savePaths(), "config-only entries are skipped");
        assertEquals(stardew, index.byTitle().get("stardew valley"));
    }

    @Test
    void trademarkSignsArticlesAndAliasesNormalize() {
        var index = LudusaviManifestService.parse(MANIFEST.getBytes(StandardCharsets.UTF_8));
        assertEquals("Celeste™", index.byTitle().get("celeste").name());
        assertEquals("Celeste™", index.byTitle().get(LudusaviManifestService.normalize("Celeste Classic")).name(), "alias");
        var witness = index.byTitle().get(LudusaviManifestService.normalize("The Witness"));
        assertEquals("The Witness", witness.name());
        assertEquals(1, witness.savePaths().size());
        assertNull(witness.savePaths().getFirst().os(), "a store-only constraint is any OS");
        assertEquals("witness", LudusaviManifestService.normalize("The Witness"));
        assertEquals("tomb raider and co 2", LudusaviManifestService.normalize("Tomb Raider & Co. 2™"));
    }

    @Test
    void normalizeStripsAccentsApostrophesAndCopyright() {
        assertEquals("pokemon rouge", LudusaviManifestService.normalize("Pokémon© Rouge"));
        assertEquals("demons souls", LudusaviManifestService.normalize("Demon's Souls"));
        assertEquals("demons souls", LudusaviManifestService.normalize("Demon’s   Souls"));
        assertEquals("", LudusaviManifestService.normalize("™"));
        assertEquals("theatre", LudusaviManifestService.normalize("Theatre"), "only a whole 'the ' article is dropped");
    }

    @Test
    void parseRejectsAnythingButAMapping() {
        byte[] list = "- a\n- b\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> LudusaviManifestService.parse(list));
    }

    @Test
    void parseSkipsMalformedEntriesAndDanglingAliases() {
        String yaml = """
                Scalar Game: just a string
                12345:
                  files:
                    <base>/x: {}
                Ghost Alias:
                  alias: Does Not Exist
                Real Game:
                  files:
                    <base>/saves: {}
                  steam:
                    id: "not-a-number"
                  cloud:
                    steam: false
                Same Steam Id Twice:
                  files:
                    <base>/a: {}
                  steam:
                    id: 77
                Same Steam Id Again:
                  steam:
                    id: 77
                """;
        Index index = LudusaviManifestService.parse(yaml.getBytes(StandardCharsets.UTF_8));
        assertNull(index.byTitle().get("scalar game"));
        assertNull(index.byTitle().get("12345"), "non-string keys are ignored");
        assertNull(index.byTitle().get("ghost alias"));
        var real = index.byTitle().get("real game");
        assertNull(real.steamId(), "a non-numeric steam id is ignored");
        assertFalse(real.steamCloud());
        assertEquals(List.of(new SavePath("<base>/saves", null)), real.savePaths(), "untagged paths count as saves");
        assertEquals("Same Steam Id Twice", index.bySteamId().get(77L).name(), "first entry wins on a duplicate steam id");
        assertEquals(1, index.bySteamId().size());
    }

    @Test
    void parseExpandsOsConstraintsPerClause() {
        String yaml = """
                Multi OS:
                  files:
                    <home>/saves:
                      tags: [save, config]
                      when:
                        - os: windows
                        - os: Mac
                        - os: windows
                    <home>/weird:
                      when:
                        - os: amiga
                    <home>/mixed:
                      when:
                        - os: linux
                        - store: gog
                    <home>/empty-when:
                      when: []
                    <home>/not-a-map: 42
                """;
        Index index = LudusaviManifestService.parse(yaml.getBytes(StandardCharsets.UTF_8));
        List<SavePath> paths = index.byTitle().get("multi os").savePaths();
        assertTrue(paths.contains(new SavePath("<home>/saves", "windows")));
        assertTrue(paths.contains(new SavePath("<home>/saves", "mac")), "os values are lowercased");
        assertEquals(2, paths.stream().filter(p -> p.path().equals("<home>/saves")).count(), "duplicate os deduplicated");
        assertTrue(paths.contains(new SavePath("<home>/weird", null)), "an unknown os is 'any'");
        assertEquals(List.of(new SavePath("<home>/mixed", null)),
                paths.stream().filter(p -> p.path().equals("<home>/mixed")).toList(), "one unconstrained clause makes the path any-OS");
        assertTrue(paths.contains(new SavePath("<home>/empty-when", null)));
        assertTrue(paths.contains(new SavePath("<home>/not-a-map", null)), "non-map constraints are treated as unconstrained");
    }

    // ---- find: download, cache, refresh, failures ----

    @Test
    void disabledNeverDownloadsOrReads() {
        LudusaviManifestService subject = service(168, false);

        assertSame(Match.NONE, subject.find(413150L, "Stardew Valley"));

        server.verify();
        verifyNoInteractions(resolver);
        assertFalse(Files.exists(cachedManifest()));
    }

    @Test
    void downloadsOnceCachesUnderLudusaviAndMatchesBySteamIdFirst() throws IOException {
        LudusaviManifestService subject = service(168, true);
        expectDownload(MANIFEST);

        Match bySteam = subject.find(413150L, "Some Other Title");
        Match byTitle = subject.find(null, "The Celeste Classic");
        Match wrongSteamFallsBackToTitle = subject.find(999L, "stardew  valley™");
        Match none = subject.find(999L, "Nothing Here");
        Match noTitle = subject.find(null, null);

        assertEquals(MatchedBy.STEAM_ID, bySteam.matchedBy());
        assertEquals("Stardew Valley", bySteam.entry().name());
        assertEquals(MatchedBy.TITLE, byTitle.matchedBy());
        assertEquals("Celeste™", byTitle.entry().name(), "alias resolves to its target");
        assertEquals(MatchedBy.TITLE, wrongSteamFallsBackToTitle.matchedBy());
        assertEquals("Stardew Valley", wrongSteamFallsBackToTitle.entry().name());
        assertSame(Match.NONE, none);
        assertSame(Match.NONE, noTitle);
        server.verify();
        assertEquals(MANIFEST, Files.readString(cachedManifest()), "cached in the cache store under ludusavi/");
        assertTrue(Files.isRegularFile(cachedManifest()));
    }

    @Test
    void freshCachedManifestIsReadWithoutDownloading() throws IOException {
        Files.createDirectories(cachedManifest().getParent());
        Files.writeString(cachedManifest(), MANIFEST);
        LudusaviManifestService subject = service(168, true);
        // No expectation registered: any request would fail the test with an AssertionError.

        Match match = subject.find(504230L, null);

        assertEquals(MatchedBy.STEAM_ID, match.matchedBy());
        assertEquals("Celeste™", match.entry().name());
        server.verify();
    }

    @Test
    void staleCachedManifestIsRefreshedAndOverwritten() throws IOException {
        Files.createDirectories(cachedManifest().getParent());
        Files.writeString(cachedManifest(), "Old Game:\n  files:\n    <base>/old: {}\n");
        Files.setLastModifiedTime(cachedManifest(), FileTime.from(Instant.now().minus(Duration.ofDays(30))));
        LudusaviManifestService subject = service(24, true);
        expectDownload(MANIFEST);

        assertSame(Match.NONE, subject.find(null, "Old Game"));
        assertEquals(MatchedBy.STEAM_ID, subject.find(413150L, null).matchedBy());
        server.verify();
        assertEquals(MANIFEST, Files.readString(cachedManifest()));
    }

    @Test
    void staleCacheIsUsedWhenTheDownloadFails() throws IOException {
        Files.createDirectories(cachedManifest().getParent());
        Files.writeString(cachedManifest(), MANIFEST);
        Files.setLastModifiedTime(cachedManifest(), FileTime.from(Instant.now().minus(Duration.ofDays(30))));
        LudusaviManifestService subject = service(24, true);
        server.expect(once(), requestTo(MANIFEST_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        Match match = subject.find(413150L, null);

        assertEquals(MatchedBy.STEAM_ID, match.matchedBy());
        assertEquals("Stardew Valley", match.entry().name());
        server.verify();
    }

    @Test
    void noCacheAndFailedDownloadGivesNoneAndDoesNotHammerTheSource() {
        LudusaviManifestService subject = service(168, true);
        server.expect(once(), requestTo(MANIFEST_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertSame(Match.NONE, subject.find(413150L, "Stardew Valley"));
        // The empty placeholder index is kept for a while: a second lookup must not download again
        // (a second request would fail the mock server's single expectation).
        assertSame(Match.NONE, subject.find(413150L, "Stardew Valley"));

        server.verify();
        assertFalse(Files.exists(cachedManifest()));
    }

    @Test
    void emptyDownloadBodyCountsAsFailure() {
        LudusaviManifestService subject = service(168, true);
        server.expect(once(), requestTo(MANIFEST_URL)).andRespond(withSuccess());

        assertSame(Match.NONE, subject.find(413150L, null));

        server.verify();
        assertFalse(Files.exists(cachedManifest()));
    }

    @Test
    void unparseableDownloadGivesNoneWithoutThrowing() {
        LudusaviManifestService subject = service(168, true);
        expectDownload("- not\n- a mapping\n");

        assertSame(Match.NONE, subject.find(null, "Stardew Valley"));

        server.verify();
        assertTrue(Files.exists(cachedManifest()), "the bytes were cached before parsing failed");
    }

    @Test
    void expiredInMemoryIndexIsReloaded() throws IOException {
        LudusaviManifestService subject = service(1, true);
        expectDownload(MANIFEST);
        assertEquals(MatchedBy.STEAM_ID, subject.find(413150L, null).matchedBy());
        server.verify();

        // Age the in-memory index past the refresh interval and the cached file along with it.
        @SuppressWarnings("unchecked")
        var indexRef = (java.util.concurrent.atomic.AtomicReference<Index>) ReflectionTestUtils.getField(subject, "index");
        Index loaded = indexRef.get();
        assertEquals(2, loaded.bySteamId().size());
        indexRef.set(new Index(loaded.bySteamId(), loaded.byTitle(), Instant.now().minus(Duration.ofHours(3))));
        Files.setLastModifiedTime(cachedManifest(), FileTime.from(Instant.now().minus(Duration.ofHours(3))));
        server.reset();
        expectDownload("New Game:\n  files:\n    <base>/new: {}\n  steam:\n    id: 1\n");

        Match match = subject.find(1L, null);

        assertEquals("New Game", match.entry().name());
        assertSame(Match.NONE, subject.find(413150L, null), "the old entries are gone");
        server.verify();
    }

    @Test
    void refreshHoursBelowOneIsClampedToOneHour() {
        LudusaviManifestService subject = service(0, true);
        assertEquals(Duration.ofHours(1), ReflectionTestUtils.getField(subject, "refreshInterval"));
    }

    @Test
    void savePathsOfAMatchSerializeWithPlaceholdersIntact() {
        Index index = LudusaviManifestService.parse(MANIFEST.getBytes(StandardCharsets.UTF_8));
        var stardew = index.bySteamId().get(413150L);
        String json = app.ister.core.games.GameSavePaths.toJson(stardew.savePaths());
        assertTrue(json.contains("<winAppData>/StardewValley/Saves"));
        assertEquals(stardew.savePaths(), app.ister.core.games.GameSavePaths.fromJson(json));
    }
}
