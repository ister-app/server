package app.ister.core.status;

import app.ister.core.eventdata.TranscodeActivityStatusData;
import app.ister.core.eventdata.TranscodeActivityStatusData.TranscodePass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscodeActivityRegistryTest {

    private TranscodeActivityRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TranscodeActivityRegistry();
    }

    private static TranscodeActivityStatusData snapshot(String node, Instant timestamp, TranscodePass... passes) {
        return new TranscodeActivityStatusData(node, timestamp, List.of(passes));
    }

    private static TranscodePass pass(String mediaFileId) {
        return new TranscodePass(mediaFileId, "movie.mkv", "video_720p", false, Instant.now());
    }

    @Test
    void keepsTheLatestSnapshotPerNode() {
        registry.update(snapshot("node-a", Instant.now(), pass("id-1")));
        registry.update(snapshot("node-a", Instant.now(), pass("id-2")));
        registry.update(snapshot("node-b", Instant.now(), pass("id-3")));

        var nodes = registry.nodesSnapshot();
        assertEquals(2, nodes.size());
        assertEquals("id-2", nodes.getFirst().getPasses().getFirst().getMediaFileId());
    }

    @Test
    void anEmptyPassListRemovesTheNode() {
        registry.update(snapshot("node-a", Instant.now(), pass("id-1")));

        registry.update(snapshot("node-a", Instant.now()));

        assertTrue(registry.nodesSnapshot().isEmpty());
    }

    @Test
    void sweepDropsEntriesOlderThanTheExpiry() {
        registry.update(snapshot("node-dead", Instant.now().minus(TranscodeActivityRegistry.EXPIRY).minusSeconds(1),
                pass("id-1")));
        registry.update(snapshot("node-live", Instant.now(), pass("id-2")));

        registry.sweep();

        var nodes = registry.nodesSnapshot();
        assertEquals(1, nodes.size());
        assertEquals("node-live", nodes.getFirst().getNodeName());
    }
}
