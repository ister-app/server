package app.ister.server;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.GameEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.GameForm;
import app.ister.core.enums.GamePlatform;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.MetadataSource;
import app.ister.core.eventdata.NewDirectoriesScanRequestedData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.GameRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.MessageSender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end game flow against real PostgreSQL and RabbitMQ: a GAME library holding a GBC ROM
 * with a cover, a PS3 folder game and a Steam install is scanned through the full pipeline
 * (scan → GameScanner → GAME_FOUND → PARAM.SFO / Ludusavi enrichment), then the game endpoints
 * serve the manifest and ranged files, keep per-user save snapshots with conflict detection,
 * retention and restore, record achievements, and the GraphQL side reports play and saves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.ister.server.tmp-dir=${java.io.tmpdir}/ister-game-it/tmp/",
        "app.ister.server.cache-dir=${java.io.tmpdir}/ister-game-it/cache/",
        "app.ister.disk.libraries[0].name=it-games",
        "app.ister.disk.libraries[0].type=GAME",
        "app.ister.disk.directories[0].name=it-game-disk",
        "app.ister.disk.directories[0].path=${java.io.tmpdir}/ister-game-it/media",
        "app.ister.disk.directories[0].library=it-games",
        "app.ister.games.save-retention=2",
        "app.ister.worker.ludusavi.refresh-hours=1",
})
@Testcontainers(disabledWithoutDocker = true)
class GameLibraryScanIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path MEDIA = Path.of(System.getProperty("java.io.tmpdir"), "ister-game-it", "media");
    private static final byte[] ROM = "GBC-ROM-BYTES-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EBOOT = new byte[4096];
    private static final byte[] EXE = "MZ-fake-exe".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SAVE_ONE = "PK-save-one".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SAVE_TWO = "PK-save-two-longer".getBytes(StandardCharsets.UTF_8);

    /** A stand-in for the Ludusavi manifest on GitHub: one Steam-id entry, one title entry. */
    private static final String MANIFEST = """
            Stardew Valley:
              files:
                <winAppData>/StardewValley/Saves:
                  tags: [save]
                  when:
                    - os: windows
              steam:
                id: 413150
              cloud:
                steam: true
            """;

    private static HttpServer manifestServer;

    /** Steam's store artwork for the Steam game, as the stub CDN serves it. */
    private static byte[] steamArtwork() throws IOException {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(6, 9, BufferedImage.TYPE_INT_RGB), "jpg", jpeg);
        return jpeg.toByteArray();
    }

    @Autowired
    private MessageSender messageSender;
    @Autowired
    private DirectoryRepository directoryRepository;
    @Autowired
    private GameRepository gameRepository;
    @Autowired
    private MediaFileRepository mediaFileRepository;
    @Autowired
    private ImageRepository imageRepository;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void manifestUrl(DynamicPropertyRegistry registry) throws IOException, InterruptedException {
        manifestServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        manifestServer.createContext("/manifest.yaml", exchange -> {
            byte[] body = MANIFEST.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        // Steam's CDN: the Steam game's cover and hero, so the test never reaches the internet.
        byte[] artwork = steamArtwork();
        manifestServer.createContext("/steam/apps/413150/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, artwork.length);
            exchange.getResponseBody().write(artwork);
            exchange.close();
        });
        // Started from a daemon thread: the JDK server's dispatcher thread inherits that, so the
        // stub can never keep a JVM alive — Spring's test-AOT processing runs this method too
        // and would otherwise hang forever after it is done.
        Thread starter = new Thread(manifestServer::start, "manifest-stub-start");
        starter.setDaemon(true);
        starter.start();
        starter.join();
        registry.add("app.ister.worker.ludusavi.manifest-url",
                () -> "http://127.0.0.1:" + manifestServer.getAddress().getPort() + "/manifest.yaml");
        registry.add("app.ister.worker.steam-artwork.cdn-base",
                () -> "http://127.0.0.1:" + manifestServer.getAddress().getPort() + "/steam/apps");
    }

    @AfterAll
    static void stopManifestServer() {
        if (manifestServer != null) {
            manifestServer.stop(0);
        }
    }

    @BeforeAll
    static void createGameLibrary() throws IOException {
        Path tetris = MEDIA.resolve("gbc/Tetris (1989)");
        Files.createDirectories(tetris);
        Files.write(tetris.resolve("Tetris.gbc"), ROM);
        Files.write(tetris.resolve("cover.jpg"), new byte[]{1, 2, 3});

        Path ps3 = MEDIA.resolve("ps3/Demon Souls (2010)/PS3_GAME");
        Files.createDirectories(ps3.resolve("USRDIR"));
        Files.write(ps3.resolve("PARAM.SFO"), sfo(Map.of("TITLE", "Demon's Souls", "TITLE_ID", "BLUS30443")));
        Files.write(ps3.resolve("USRDIR/EBOOT.BIN"), EBOOT);
        Files.write(ps3.resolve("ICON0.PNG"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        Path steam = MEDIA.resolve("steam/Stardew Valley (2016)");
        Files.createDirectories(steam.resolve("Stardew Valley/Content"));
        Files.writeString(steam.resolve("appmanifest_413150.acf"), """
                "AppState"
                {
                	"appid"		"413150"
                	"name"		"Stardew Valley"
                	"installdir"		"Stardew Valley"
                	"buildid"		"777"
                }
                """);
        Files.write(steam.resolve("Stardew Valley/Stardew Valley.exe"), EXE);
        Files.write(steam.resolve("Stardew Valley/Content/data.xnb"), new byte[100]);
    }

    @Test
    void scanningAGameLibraryCreatesGamesServesFilesAndKeepsSaves() throws Exception {
        DirectoryEntity directory = java.util.stream.StreamSupport
                .stream(directoryRepository.findAll().spliterator(), false)
                .filter(dir -> dir.getDirectoryType() == DirectoryType.LIBRARY)
                .findFirst().orElseThrow();
        messageSender.sendNewDirectoriesScanRequested(NewDirectoriesScanRequestedData.builder()
                .eventType(EventType.NEW_DIRECTORIES_SCAN_REQUEST)
                .directoryEntityUUID(directory.getId())
                .build(), directory.getName());

        verifyScanCreatedTheGames();

        RestTemplate rest = new RestTemplate();
        GameEntity steam = gameByName("Stardew Valley");
        GameEntity tetris = gameByName("Tetris");
        verifyManifestAndRangedFiles(rest, steam, tetris);
        verifySaves(rest, tetris);
        verifyAchievements(rest, steam);
        verifyGraphQl(rest, tetris, steam);
    }

    private void verifyScanCreatedTheGames() {
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertEquals(3, gameRepository.findAll().size(), "gbc + ps3 + steam"));

        // The cover and the ROM are separate FILE_SCAN_REQUESTED messages: whichever lands first
        // creates the game row, so the ROM's file and size can arrive a moment after the row.
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            GameEntity tetris = gameByName("Tetris");
            assertEquals(ROM.length, tetris.getSizeBytes(), "ROM attached and sized");
            assertEquals(1, tetris.getFileCount());
        });
        GameEntity tetris = gameByName("Tetris");
        assertEquals(GamePlatform.GBC, tetris.getPlatform());
        assertEquals(GameForm.ROM, tetris.getForm());
        assertEquals(1989, tetris.getPathYear());
        MediaFileEntity rom = mediaFileRepository.findByGameEntityId(tetris.getId()).getFirst();
        assertTrue(rom.isGameEntryFile());
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertEquals(1, imageRepository.findByGameEntityId(tetris.getId()).size(), "cover.jpg attached"));

        // The worker reads PARAM.SFO asynchronously (GAME_FOUND).
        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            GameEntity ps3 = gameByName("Demon Souls");
            assertEquals("Demon's Souls", ps3.getTitle(), "TITLE from PARAM.SFO");
            assertEquals("BLUS30443", ps3.getTitleId());
        });
        GameEntity ps3 = gameByName("Demon Souls");
        assertEquals(GameForm.PS3_FOLDER, ps3.getForm());
        assertEquals("PS3_GAME/USRDIR/EBOOT.BIN", ps3.getEntryPath());
        assertEquals(MEDIA.resolve("ps3/Demon Souls (2010)").toString(), ps3.getRootPath());
        assertEquals(3, ps3.getFileCount(), "PARAM.SFO + EBOOT.BIN + ICON0.PNG, no rows per file");
        assertTrue(mediaFileRepository.findByGameEntityId(ps3.getId()).isEmpty(), "folder games get no media-file rows");
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertFalse(imageRepository.findByGameEntityId(ps3.getId()).isEmpty(), "ICON0.PNG becomes the cover"));

        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            GameEntity steam = gameByName("Stardew Valley");
            assertEquals("STEAM_ID", steam.getManifestMatchedBy(), "Ludusavi matched on the Steam app id");
        });
        GameEntity steam = gameByName("Stardew Valley");
        // No cover in its folder: Steam's store artwork (the stub CDN) fills both slots.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var images = imageRepository.findByGameEntityId(steam.getId());
            assertTrue(images.stream().anyMatch(i -> i.getType() == ImageType.COVER && i.getSource() == MetadataSource.STEAM),
                    "Steam cover");
            assertTrue(images.stream().anyMatch(i -> i.getType() == ImageType.BACKGROUND && i.getSource() == MetadataSource.STEAM),
                    "Steam background");
        });
        assertEquals(GameForm.STEAM, steam.getForm());
        assertEquals(413150L, steam.getSteamAppId());
        assertEquals("Stardew Valley", steam.getSteamInstallDir());
        assertEquals(777L, steam.getSteamBuildId());
        assertEquals(2, steam.getFileCount(), "the install tree; the .acf travels separately");
        assertTrue(steam.isSteamCloud());
        assertTrue(steam.getManifestSavePaths().contains("<winAppData>/StardewValley/Saves"));
    }

    private void verifyManifestAndRangedFiles(RestTemplate rest, GameEntity steam, GameEntity tetris) throws Exception {
        JsonNode manifest = MAPPER.readTree(rest.exchange(url("/game/%s/manifest", steam.getId()),
                HttpMethod.GET, new HttpEntity<>(auth()), String.class).getBody());
        assertEquals("STEAM", manifest.get("form").asText());
        assertEquals("413150", manifest.get("steamAppId").asText());
        var paths = new java.util.ArrayList<String>();
        manifest.get("files").forEach(f -> paths.add(f.get("path").asText()));
        assertTrue(paths.contains("Stardew Valley/Stardew Valley.exe"), paths.toString());
        assertTrue(paths.contains("Stardew Valley/Content/data.xnb"));
        assertTrue(paths.contains("appmanifest_413150.acf"), "the Steam manifest is part of the download");

        ResponseEntity<byte[]> exe = rest.exchange(url("/game/%s/file/Stardew Valley/Stardew Valley.exe", steam.getId()),
                HttpMethod.GET, new HttpEntity<>(auth()), byte[].class);
        assertEquals(200, exe.getStatusCode().value());
        assertArrayEquals(EXE, exe.getBody());

        HttpHeaders range = auth();
        range.set(HttpHeaders.RANGE, "bytes=4-7");
        ResponseEntity<byte[]> chunk = rest.exchange(url("/game/%s/file/Tetris.gbc", tetris.getId()),
                HttpMethod.GET, new HttpEntity<>(range), byte[].class);
        assertEquals(206, chunk.getStatusCode().value());
        assertEquals("ROM-", new String(chunk.getBody(), StandardCharsets.UTF_8));
        assertEquals("bytes 4-7/" + ROM.length, chunk.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));

        HttpEntity<Void> authed = new HttpEntity<>(auth());
        String traversal = url("/game/%s/file/../../gbc/Tetris (1989)/Tetris.gbc", steam.getId());
        assertThrows(HttpClientErrorException.class,
                () -> rest.exchange(traversal, HttpMethod.GET, authed, byte[].class), "traversal is refused");
        String missing = url("/game/%s/file/nope.bin", tetris.getId());
        assertThrows(HttpClientErrorException.NotFound.class,
                () -> rest.exchange(missing, HttpMethod.GET, authed, byte[].class));
    }

    private void verifySaves(RestTemplate rest, GameEntity game) throws Exception {
        HttpHeaders zip = auth();
        zip.setContentType(MediaType.parseMediaType("application/zip"));

        JsonNode first = post(rest, url("/game/%s/saves?baseVersion=0&device=laptop", game.getId()), zip, SAVE_ONE);
        assertEquals(1, first.get("version").asInt());
        assertEquals("laptop", first.get("deviceName").asText());

        // A second device that never saw v1 uploads from base 0: that is a conflict.
        String staleUpload = url("/game/%s/saves?baseVersion=0&device=desktop", game.getId());
        HttpClientErrorException conflict = assertThrows(HttpClientErrorException.class,
                () -> post(rest, staleUpload, zip, SAVE_TWO));
        assertEquals(409, conflict.getStatusCode().value());
        assertTrue(conflict.getResponseBodyAsString().contains("\"latestVersion\":1"), conflict.getResponseBodyAsString());

        JsonNode forced = post(rest, url("/game/%s/saves?baseVersion=0&force=true&device=desktop", game.getId()), zip, SAVE_TWO);
        assertEquals(2, forced.get("version").asInt());

        ResponseEntity<byte[]> download = rest.exchange(url("/game/%s/saves/1", game.getId()),
                HttpMethod.GET, new HttpEntity<>(auth()), byte[].class);
        assertArrayEquals(SAVE_ONE, download.getBody());

        // Restoring v1 writes it as v3; retention 2 then drops v1 itself.
        JsonNode restored = MAPPER.readTree(rest.exchange(url("/game/%s/saves/1/restore", game.getId()),
                HttpMethod.POST, new HttpEntity<>(auth()), String.class).getBody());
        assertEquals(3, restored.get("version").asInt());
        assertEquals("restored from v1", restored.get("note").asText());
        JsonNode list = MAPPER.readTree(rest.exchange(url("/game/%s/saves", game.getId()),
                HttpMethod.GET, new HttpEntity<>(auth()), String.class).getBody());
        assertEquals(2, list.size(), "retention keeps the newest two");
        assertEquals(3, list.get(0).get("version").asInt());
        assertEquals(2, list.get(1).get("version").asInt());
        ResponseEntity<byte[]> head = rest.exchange(url("/game/%s/saves/3", game.getId()),
                HttpMethod.GET, new HttpEntity<>(auth()), byte[].class);
        assertArrayEquals(SAVE_ONE, head.getBody(), "the restored head carries v1's bytes");
        assertThrows(HttpClientErrorException.NotFound.class, () -> rest.exchange(url("/game/%s/saves/1", game.getId()),
                HttpMethod.GET, new HttpEntity<>(auth()), byte[].class));

        // Another user sees none of this.
        HttpHeaders other = new HttpHeaders();
        other.setBearerAuth("other-token");
        JsonNode othersList = MAPPER.readTree(rest.exchange(url("/game/%s/saves", game.getId()),
                HttpMethod.GET, new HttpEntity<>(other), String.class).getBody());
        assertEquals(0, othersList.size());
    }

    private void verifyAchievements(RestTemplate rest, GameEntity game) throws Exception {
        HttpHeaders json = auth();
        json.setContentType(MediaType.APPLICATION_JSON);
        String report = """
                {"source":"STEAM","deviceName":"laptop","achievements":[
                  {"externalId":"ACH_1","name":"Greenhorn","description":"Earn 15,000g","hidden":false,"sortOrder":0,"unlockedAt":"2026-10-01T10:00:00Z"},
                  {"externalId":"ACH_2","name":"Cowpoke","hidden":false,"sortOrder":1}
                ]}
                """;
        JsonNode summary = MAPPER.readTree(rest.exchange(url("/game/%s/achievements", game.getId()),
                HttpMethod.POST, new HttpEntity<>(report, json), String.class).getBody());
        assertEquals(2, summary.get("total").asInt());
        assertEquals(1, summary.get("unlocked").asInt());
        // Reporting again is idempotent.
        JsonNode again = MAPPER.readTree(rest.exchange(url("/game/%s/achievements", game.getId()),
                HttpMethod.POST, new HttpEntity<>(report, json), String.class).getBody());
        assertEquals(2, again.get("total").asInt());
        assertEquals(1, again.get("unlocked").asInt());
    }

    private void verifyGraphQl(RestTemplate rest, GameEntity tetris, GameEntity steam) throws Exception {
        JsonNode played = graphql(rest, """
                mutation { recordGamePlayed(gameId: "%s", durationMs: 90000) { id progressInMilliseconds } }
                """.formatted(tetris.getId()));
        assertEquals(90000, played.at("/data/recordGamePlayed/progressInMilliseconds").asInt());

        JsonNode game = graphql(rest, """
                query { gameById(id: "%s") { title platform form launcher { kind retroArchCore } playTimeMs lastPlayed
                  saves { version note } achievements { total unlocked } savePathSource } }
                """.formatted(tetris.getId()));
        JsonNode g = game.at("/data/gameById");
        assertEquals("Tetris", g.get("title").asText());
        assertEquals("GBC", g.get("platform").asText());
        assertEquals("RETROARCH", g.at("/launcher/kind").asText());
        assertEquals("gambatte", g.at("/launcher/retroArchCore").asText());
        assertEquals(90000, g.get("playTimeMs").asInt());
        assertNotNull(g.get("lastPlayed").asText(null));
        assertEquals(2, g.get("saves").size());
        assertEquals("NONE", g.get("savePathSource").asText());

        JsonNode steamGame = graphql(rest, """
                query { gameById(id: "%s") { steamAppId steamCloud savePathSource savePaths { path os }
                  achievements { total unlocked list { externalId unlockedAt } } } }
                """.formatted(steam.getId()));
        JsonNode s = steamGame.at("/data/gameById");
        assertEquals("413150", s.get("steamAppId").asText());
        assertTrue(s.get("steamCloud").asBoolean());
        assertEquals("MANIFEST", s.get("savePathSource").asText());
        assertEquals("<winAppData>/StardewValley/Saves", s.at("/savePaths/0/path").asText());
        assertEquals("windows", s.at("/savePaths/0/os").asText());
        assertEquals(2, s.at("/achievements/total").asInt());
        assertEquals(1, s.at("/achievements/unlocked").asInt());

        JsonNode recent = graphql(rest, "query { recentlyWatched { type game { id } } }");
        boolean found = false;
        for (JsonNode entry : recent.at("/data/recentlyWatched")) {
            if ("GAME".equals(entry.get("type").asText()) && tetris.getId().toString().equals(entry.at("/game/id").asText())) {
                found = true;
            }
        }
        assertTrue(found, "the played game is in continue watching: " + recent);

        JsonNode override = graphql(rest, """
                mutation { updateGameSavePaths(gameId: "%s", savePaths: ["<home>/.config/tetris"]) { savePathSource savePaths { path os } } }
                """.formatted(tetris.getId()));
        assertEquals("ADMIN", override.at("/data/updateGameSavePaths/savePathSource").asText());
        assertEquals("<home>/.config/tetris", override.at("/data/updateGameSavePaths/savePaths/0/path").asText());
        assertNull(override.at("/data/updateGameSavePaths/savePaths/0/os").asText(null));

        JsonNode platforms = graphql(rest, "query { gamePlatforms { platform folderNames entryExtensions launcher { kind } } }");
        assertTrue(platforms.at("/data/gamePlatforms").size() >= 10);
    }

    private JsonNode graphql(RestTemplate rest, String query) throws Exception {
        HttpHeaders headers = auth();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = MAPPER.writeValueAsString(Map.of("query", query));
        JsonNode response = MAPPER.readTree(rest.exchange(url("/graphql"), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class).getBody());
        assertNull(response.get("errors"), "GraphQL errors: " + response.get("errors"));
        return response;
    }

    private JsonNode post(RestTemplate rest, String url, HttpHeaders headers, byte[] body) throws Exception {
        return MAPPER.readTree(rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class).getBody());
    }

    private String url(String path, Object... args) {
        return "http://localhost:" + port + path.formatted(args);
    }

    private static HttpHeaders auth() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        return headers;
    }

    private GameEntity gameByName(String name) {
        return gameRepository.findAll().stream()
                .filter(game -> name.equals(game.getName()))
                .findFirst().orElseThrow();
    }

    /** A PARAM.SFO (PSF) with string entries: header, index, key table, data table. */
    private static byte[] sfo(Map<String, String> values) {
        Map<String, String> ordered = new LinkedHashMap<>(values);
        ByteArrayOutputStream keys = new ByteArrayOutputStream();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ByteBuffer index = ByteBuffer.allocate(16 * ordered.size()).order(ByteOrder.LITTLE_ENDIAN);
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            int keyOffset = keys.size();
            keys.writeBytes(entry.getKey().getBytes(StandardCharsets.US_ASCII));
            keys.write(0);
            int dataOffset = data.size();
            byte[] text = entry.getValue().getBytes(StandardCharsets.UTF_8);
            data.writeBytes(text);
            data.write(0);
            while (data.size() % 4 != 0) {
                data.write(0);
            }
            index.putShort((short) keyOffset).putShort((short) 0x0204).putInt(text.length + 1).putInt(text.length + 1).putInt(dataOffset);
        }
        int keyTableStart = 0x14 + index.capacity();
        int dataTableStart = keyTableStart + keys.size();
        ByteBuffer header = ByteBuffer.allocate(0x14).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 0).put((byte) 'P').put((byte) 'S').put((byte) 'F');
        header.putInt(0x0101).putInt(keyTableStart).putInt(dataTableStart).putInt(ordered.size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header.array());
        out.writeBytes(index.array());
        out.writeBytes(keys.toByteArray());
        out.writeBytes(data.toByteArray());
        return out.toByteArray();
    }
}
