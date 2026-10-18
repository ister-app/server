package app.ister.disk.scanner.steam;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VdfParserTest {

    @Test
    void parsesAnAppManifest() {
        String acf = """
                "AppState"
                {
                	"appid"		"413150"
                	"Universe"		"1"
                	"name"		"Stardew Valley"
                	"StateFlags"		"4"
                	"installdir"		"Stardew Valley"
                	"buildid"		"12345678"
                	"SizeOnDisk"		"600000000"
                	"UserConfig"
                	{
                		"language"		"english"
                	}
                	"InstalledDepots"
                	{
                		"413151"
                		{
                			"manifest"		"1"
                		}
                	}
                }
                """;
        Map<String, Object> state = VdfParser.body(VdfParser.parse(acf));
        assertEquals(413150L, VdfParser.number(state, "appid").orElseThrow());
        assertEquals("Stardew Valley", VdfParser.string(state, "name").orElseThrow());
        assertEquals("Stardew Valley", VdfParser.string(state, "InstallDir").orElseThrow(), "case-insensitive");
        assertEquals("english", VdfParser.string(VdfParser.map(state, "UserConfig").orElseThrow(), "language").orElseThrow());
        assertTrue(VdfParser.map(state, "InstalledDepots").orElseThrow().containsKey("413151"));
    }

    @Test
    void handlesEscapesCommentsAndUnquotedTokens() {
        String vdf = """
                // a comment
                "libraryfolders"
                {
                	"0" { "path" "C:\\\\Program Files (x86)\\\\Steam" "label" "" }
                	1 { path "/home/me/.steam/steam" }
                	"quoted" "say \\"hi\\""
                }
                """;
        Map<String, Object> body = VdfParser.body(VdfParser.parse(vdf));
        assertEquals("C:\\Program Files (x86)\\Steam", VdfParser.string(VdfParser.map(body, "0").orElseThrow(), "path").orElseThrow());
        assertEquals("/home/me/.steam/steam", VdfParser.string(VdfParser.map(body, "1").orElseThrow(), "path").orElseThrow());
        assertEquals("say \"hi\"", VdfParser.string(body, "quoted").orElseThrow());
    }

    @Test
    void rejectsUnterminatedBlocks() {
        assertThrows(IllegalArgumentException.class, () -> VdfParser.parse("\"a\" { \"b\" \"c\""));
        assertThrows(IllegalArgumentException.class, () -> VdfParser.parse("\"a\" \"unterminated"));
    }
}
