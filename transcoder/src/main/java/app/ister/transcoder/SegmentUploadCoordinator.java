package app.ister.transcoder;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.node.MediaFileInputResolver;
import app.ister.core.node.RemoteNodeClient;
import app.ister.core.storage.TmpStore;
import app.ister.core.storage.TmpStoreProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Pushes what a transcode produces (segments, playlists, done markers) to wherever another node
 * will serve it from: the cluster-shared tmp store when there is one, else the node that asked
 * for the playback or the owner of the file. Split out of {@link HlsService}, which decides
 * <em>when</em> to start passes; this class only knows <em>where</em> the output must go and runs
 * the watcher thread that ships each segment once it is stable.
 */
@Component
@Slf4j
public class SegmentUploadCoordinator {

    private final HlsTranscodeService transcodeService;
    private final RemoteNodeClient remoteNodeClient;
    private final MediaFileInputResolver inputResolver;
    private final TmpStoreProvider tmpStoreProvider;

    /**
     * Where produced segments/playlists of a file must be pushed to besides the local tmp dir:
     * the requesting node of an S3 file, or the owner of a LOCAL file this helper transcodes.
     * Keyed by media file id, filled when a pass starts, read by the playlist re-upload hook.
     */
    private final ConcurrentHashMap<UUID, String> uploadTargets = new ConcurrentHashMap<>();

    /** This node's public URL, as other nodes and clients see it. */
    @Value("${app.ister.server.url:}")
    private String ownUrl;

    /** After the pass completes, keep retrying failed segment uploads for at most this long. */
    @Value("${app.ister.transcoder.hls.upload-drain-timeout-ms:300000}")
    private long uploadDrainTimeoutMs;

    /** Daemon thread pool for segment-upload watcher threads (remote transcoding). */
    private final ExecutorService watcherExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "segment-watcher");
        t.setDaemon(true);
        return t;
    });


    public SegmentUploadCoordinator(HlsTranscodeService transcodeService, RemoteNodeClient remoteNodeClient,
                                    MediaFileInputResolver inputResolver, TmpStoreProvider tmpStoreProvider) {
        this.transcodeService = transcodeService;
        this.remoteNodeClient = remoteNodeClient;
        this.inputResolver = inputResolver;
        this.tmpStoreProvider = tmpStoreProvider;
    }

    /** This node's public URL as configured, possibly blank. */
    String ownUrl() {
        return ownUrl;
    }

    /** Ships every stable segment with the given prefix to the target until the pass is done and all are uploaded. */
    void watch(Path cacheDirPath, String segmentPrefix, UploadTarget target, UUID mediaFileId,
               CompletableFuture<Void> passFuture) {
        watcherExecutor.submit(() -> watchAndUpload(cacheDirPath, segmentPrefix, target, mediaFileId, passFuture));
    }

    /** A requester that is another node becomes the push target for everything this node produces for the file. */
    void rememberUploadTarget(UUID mediaFileId, String requestingNodeUrl) {
        if (mediaFileId == null || requestingNodeUrl == null || requestingNodeUrl.isBlank()) {
            return;
        }
        if (ownUrl != null && !ownUrl.isBlank() && stripSlash(ownUrl).equals(stripSlash(requestingNodeUrl))) {
            uploadTargets.remove(mediaFileId);
        } else {
            uploadTargets.put(mediaFileId, stripSlash(requestingNodeUrl));
        }
    }

    String requesterOf(UUID mediaFileId) {
        return mediaFileId == null ? null : uploadTargets.get(mediaFileId);
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * The node the produced segments and playlists must be pushed to, if any: the node that
     * requested playback when that is not this node (an S3 file transcoded by another attached
     * node, or a helper), else — for a LOCAL file owned elsewhere — its owner. Empty when this
     * node serves the file itself.
     */
    Optional<UploadTarget> uploadTarget(MediaFileEntity mediaFile) {
        Optional<TmpStore> shared = tmpStoreProvider.shared();
        if (shared.isPresent()) {
            // Everything goes to the cluster-shared store; whoever serves the playback reads
            // it through from there, so the requester and the owner no longer matter.
            return Optional.of(new SharedTmpTarget(shared.get()));
        }
        String requester = requesterOf(mediaFile.getId());
        if (requester != null) {
            return Optional.of(new NodeTarget(requester));
        }
        return inputResolver.remoteNodeUrl(mediaFile).map(NodeTarget::new);
    }

    /** Where a produced file must be published besides the local tmp dir. */
    interface UploadTarget {
        void upload(UUID mediaFileId, Path file) throws IOException;
    }

    private final class NodeTarget implements UploadTarget {
        private final String nodeUrl;

        NodeTarget(String nodeUrl) {
            this.nodeUrl = nodeUrl;
        }

        @Override
        public void upload(UUID mediaFileId, Path file) throws IOException {
            remoteNodeClient.uploadFile(nodeUrl, mediaFileId, file);
        }

        @Override
        public String toString() {
            return nodeUrl;
        }
    }

    private static final class SharedTmpTarget implements UploadTarget {
        private final TmpStore store;

        SharedTmpTarget(TmpStore store) {
            this.store = store;
        }

        @Override
        public void upload(UUID mediaFileId, Path file) throws IOException {
            store.put(mediaFileId, file);
        }

        @Override
        public String toString() {
            return "shared tmp store";
        }
    }

    // ========== Segment watcher ==========

    private void watchAndUpload(Path cacheDirPath, String prefix, UploadTarget target,
                                 UUID mediaFileId, CompletableFuture<Void> passFuture) {
        Set<String> uploaded = new HashSet<>();
        long drainDeadline = -1;
        boolean running = true;
        while (running && (!passFuture.isDone() || !allUploaded(cacheDirPath, prefix, uploaded))) {
            if (passFuture.isDone()) {
                // Pass finished but uploads are incomplete (e.g. peer unreachable): keep
                // retrying for a bounded drain window instead of looping forever.
                if (drainDeadline < 0) {
                    drainDeadline = System.currentTimeMillis() + uploadDrainTimeoutMs;
                }
                running = System.currentTimeMillis() <= drainDeadline;
                if (!running) {
                    log.warn("Giving up uploading remaining segments for {} after {} ms", cacheDirPath, uploadDrainTimeoutMs);
                }
            }
            running = running && scanAndUploadBatch(cacheDirPath, prefix, target, mediaFileId, uploaded);
        }
        if (passFuture.isDone() && !passFuture.isCompletedExceptionally()) {
            uploadDoneMarker(cacheDirPath, prefix, target, mediaFileId);
        }
    }

    /**
     * The done marker goes last: a reader that sees it may serve every segment without waiting on
     * stability, so it must never arrive before the segments themselves.
     */
    private void uploadDoneMarker(Path cacheDirPath, String prefix, UploadTarget target, UUID mediaFileId) {
        Path marker = cacheDirPath.resolve(HlsTranscodeService.DONE_MARKER_PREFIX + prefix);
        if (!Files.exists(marker)) {
            return;
        }
        try {
            target.upload(mediaFileId, marker);
        } catch (IOException e) {
            log.warn("Done marker upload failed for {}: {}", marker, e.getMessage());
        }
    }

    private boolean scanAndUploadBatch(Path cacheDirPath, String prefix, UploadTarget target,
                                        UUID mediaFileId, Set<String> uploaded) {
        try (Stream<Path> files = Files.list(cacheDirPath)) {
            files.filter(p -> p.getFileName().toString().startsWith(prefix)
                           && p.getFileName().toString().endsWith(".ts")
                           && !uploaded.contains(p.getFileName().toString()))
                 .forEach(p -> tryUploadSegment(target, mediaFileId, p, uploaded));
            Thread.sleep(500);
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.nio.file.NoSuchFileException _) {
            log.debug("Cache dir removed, stopping watcher: {}", cacheDirPath);
            return false;
        } catch (IOException e) {
            log.warn("Watcher error in {}", cacheDirPath, e);
            return false;
        }
    }

    private void tryUploadSegment(UploadTarget target, UUID mediaFileId, Path p, Set<String> uploaded) {
        try {
            if (transcodeService.stableSegmentOrNull(p) != null) {
                target.upload(mediaFileId, p);
                uploaded.add(p.getFileName().toString());
            }
        } catch (IOException e) {
            log.warn("Segment upload failed: {}", p, e);
        }
    }

    private boolean allUploaded(Path cacheDir, String prefix, Set<String> uploaded) {
        try (Stream<Path> files = Files.list(cacheDir)) {
            List<String> all = files
                    .filter(p -> p.getFileName().toString().startsWith(prefix)
                              && p.getFileName().toString().endsWith(".ts"))
                    .map(p -> p.getFileName().toString())
                    .toList();
            return !all.isEmpty() && uploaded.containsAll(all);
        } catch (IOException _) {
            return false;
        }
    }

}
