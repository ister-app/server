package app.ister.transcoder;

import app.ister.core.storage.TmpStore;
import app.ister.core.storage.TmpStoreProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;

/**
 * The request-side waits of HLS playback: polls the local tmp dir, and with a cluster-shared tmp
 * store ({@code app.ister.server.tmp-s3-connection}) reads through to it, for the playlists and
 * segments a pass on another node produces. Split out of {@link HlsService} so the loops that
 * hold an HTTP thread are in one place.
 */
@Component
@Slf4j
public class SharedTmpReadThrough {

    private final TmpStoreProvider tmpStoreProvider;
    private final HlsTranscodeService transcodeService;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    /** Same budget as HlsTranscodeService.waitForSegment, for segments produced on another node via the shared tmp store. */
    @Value("${app.ister.server.hls.segment-timeout-ms:60000}")
    private long segmentTimeoutMs;

    public SharedTmpReadThrough(TmpStoreProvider tmpStoreProvider, HlsTranscodeService transcodeService) {
        this.tmpStoreProvider = tmpStoreProvider;
        this.transcodeService = transcodeService;
    }

    /**
     * Pulls a playlist/marker of the media file from the shared tmp store into the local tmp dir
     * when it is not there yet. Local first: the node that ran the pass never round-trips.
     */
    boolean syncFromShared(UUID mediaFileId, Path localFile) {
        if (Files.exists(localFile)) {
            return true;
        }
        Optional<TmpStore> shared = tmpStoreProvider.shared();
        if (shared.isEmpty()) {
            return false;
        }
        try {
            return shared.get().copyToLocal(mediaFileId, localFile.getFileName().toString(), localFile);
        } catch (IOException e) {
            log.debug("Could not read {} from the shared tmp store: {}", localFile.getFileName(), e.getMessage());
            return false;
        }
    }

    /** A segment plus, when the pass finished elsewhere, its done marker, so the local fast path applies. */
    void syncSegmentFromShared(UUID mediaFileId, Path segmentFile, String segmentPrefix) {
        if (tmpStoreProvider.shared().isEmpty()) {
            return;
        }
        syncFromShared(mediaFileId, cacheDir(mediaFileId).resolve(HlsTranscodeService.DONE_MARKER_PREFIX + segmentPrefix));
        syncFromShared(mediaFileId, segmentFile);
    }

    /**
     * Waits for a segment: the local pass, and — with a shared tmp store — a pass running on
     * another attached node, whose finished segments appear in the store one by one.
     */
    Path waitForSegment(UUID mediaFileId, Path cacheFile, String passKey, String segmentPrefix) throws IOException {
        if (tmpStoreProvider.shared().isEmpty() || transcodeService.isPassActive(passKey)) {
            return transcodeService.waitForSegment(cacheFile, passKey);
        }
        long deadline = System.currentTimeMillis() + segmentTimeoutMs;
        long delay = 250;
        while (System.currentTimeMillis() < deadline) {
            if (transcodeService.isPassActive(passKey)) {
                // this node picked the pass up after all
                return transcodeService.waitForSegment(cacheFile, passKey);
            }
            syncSegmentFromShared(mediaFileId, cacheFile, segmentPrefix);
            Path stable = transcodeService.stableSegmentOrNull(cacheFile);
            if (stable != null) {
                return stable;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for segment");
            }
            delay = Math.min(delay * 2, 1000);
        }
        throw new IOException("Timeout waiting for HLS segment from the shared tmp store: " + cacheFile);
    }

    String waitForMasterPlaylist(UUID mediaFileId, Path cacheFile, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            syncFromShared(mediaFileId, cacheFile);
            if (Files.exists(cacheFile)) {
                String content = Files.readString(cacheFile);
                // Guard against reading the file between creation and the write completing
                if (!content.isBlank()) {
                    return content;
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for master playlist");
            }
        }
        throw new IOException("Timeout waiting for master playlist: " + cacheFile);
    }

    private Path cacheDir(UUID mediaFileId) {
        return Paths.get(tmpDir, mediaFileId.toString());
    }
}
