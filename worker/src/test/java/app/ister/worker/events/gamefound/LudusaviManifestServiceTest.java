package app.ister.worker.events.gamefound;

import app.ister.core.games.GameSavePaths.SavePath;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LudusaviManifestServiceTest {

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
}
