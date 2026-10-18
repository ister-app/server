package app.ister.disk.games;

import app.ister.core.entity.GameEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.enums.GameForm;
import app.ister.core.enums.GamePlatform;
import app.ister.core.games.GameVersion;
import app.ister.core.repository.GameRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.UserService;
import app.ister.core.storage.FileAccess;
import app.ister.core.storage.LocalCopy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract an installing client depends on: the manifest's version/ETag to detect a
 * changed game, and revalidatable (not immutable) game files.
 */
class GameResourceControllerTest {

    private static final Instant ROM_MODIFIED = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant CUE_MODIFIED = Instant.parse("2026-09-01T12:00:00Z");

    private final GameRepository gameRepository = mock(GameRepository.class);
    private final MediaFileRepository mediaFileRepository = mock(MediaFileRepository.class);
    private final LocalCopy localCopy = mock(LocalCopy.class);
    private final UUID gameId = UUID.randomUUID();
    private GameEntity game;
    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        LibraryAccessService access = mock(LibraryAccessService.class);
        when(access.canAccess(any(LibraryEntity.class), any())).thenReturn(true);
        LibraryEntity library = LibraryEntity.builder().name("Games").build();
        library.setId(UUID.randomUUID());
        game = GameEntity.builder()
                .libraryEntity(library)
                .platform(GamePlatform.SNES)
                .form(GameForm.ROM)
                .name("Chrono")
                .sizeBytes(12)
                .fileCount(2)
                .dateUpdated(Instant.parse("2026-10-18T10:00:00Z"))
                .build();
        game.setId(gameId);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        Path rom = tempDir.resolve("chrono.sfc");
        Files.write(rom, "hello world!".getBytes());
        Files.setLastModifiedTime(rom, FileTime.from(ROM_MODIFIED));
        MediaFileEntity romRow = MediaFileEntity.builder().path("/lib/snes/Chrono (1995)/chrono.sfc")
                .size(12).fileLastModifiedTime(ROM_MODIFIED).gameEntryFile(true).build();
        MediaFileEntity cueRow = MediaFileEntity.builder().path("/lib/snes/Chrono (1995)/chrono.cue")
                .size(0).fileLastModifiedTime(CUE_MODIFIED).gameEntryFile(false).build();
        when(mediaFileRepository.findByGameEntityId(gameId)).thenReturn(List.of(romRow, cueRow));
        when(localCopy.localPathOrNull(romRow)).thenReturn(rom);

        mockMvc = MockMvcBuilders.standaloneSetup(new GameResourceController(gameRepository, mediaFileRepository,
                mock(GameTreeLister.class), mock(GameSaveService.class), mock(GameAchievementService.class),
                access, mock(UserService.class), localCopy, mock(FileAccess.class))).build();
    }

    @Test
    void manifestCarriesVersionAndNewestFileTime() throws Exception {
        String version = GameVersion.of(game);

        mockMvc.perform(get("/game/" + gameId + "/manifest"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + gameId + "-" + version + "\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-cache"))
                .andExpect(jsonPath("$.version").value(version))
                .andExpect(jsonPath("$.updatedAt").value(ROM_MODIFIED.toString()))
                .andExpect(jsonPath("$.fileCount").value(2))
                .andExpect(jsonPath("$.files[0].path").value("chrono.cue"));
    }

    @Test
    void manifestVersionIsTheSteamBuildIdForASteamGame() throws Exception {
        game.setSteamBuildId(987654L);

        mockMvc.perform(get("/game/" + gameId + "/manifest"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + gameId + "-987654\""))
                .andExpect(jsonPath("$.version").value("987654"));
    }

    @Test
    void manifestAnswers304OnAMatchingEtag() throws Exception {
        String etag = "\"" + gameId + "-" + GameVersion.of(game) + "\"";

        mockMvc.perform(get("/game/" + gameId + "/manifest").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag));
    }

    @Test
    void aChangedGameInvalidatesTheOldManifestEtag() throws Exception {
        String oldEtag = "\"" + gameId + "-" + GameVersion.of(game) + "\"";
        game.setDateUpdated(game.getDateUpdated().plusSeconds(60));

        mockMvc.perform(get("/game/" + gameId + "/manifest").header(HttpHeaders.IF_NONE_MATCH, oldEtag))
                .andExpect(status().isOk());
    }

    @Test
    void fileIsRevalidatableNotImmutable() throws Exception {
        MvcResult result = mockMvc.perform(get("/game/" + gameId + "/file/chrono.sfc"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-cache"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"12-" + ROM_MODIFIED.toEpochMilli() + "\""))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().exists(HttpHeaders.LAST_MODIFIED))
                .andReturn();
        result.getAsyncResult();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).doesNotContain("immutable");
        assertThat(result.getResponse().getHeader(HttpHeaders.LAST_MODIFIED)).contains("01 Oct 2026 12:00:00 GMT");
    }

    @Test
    void fileAnswers304OnAMatchingEtag() throws Exception {
        mockMvc.perform(get("/game/" + gameId + "/file/chrono.sfc")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"12-" + ROM_MODIFIED.toEpochMilli() + "\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, "\"12-" + ROM_MODIFIED.toEpochMilli() + "\""));
    }

    @Test
    void fileStillServesARange() throws Exception {
        MvcResult result = mockMvc.perform(get("/game/" + gameId + "/file/chrono.sfc")
                        .header(HttpHeaders.RANGE, "bytes=6-10"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 6-10/12"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-cache"))
                .andReturn();
        result.getAsyncResult();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isPartialContent())
                .andExpect(content().string("world"));
    }
}
