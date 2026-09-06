package app.ister.disk.events.subtitleextract;

import app.ister.core.EventHandlingException;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.node.MediaFileInputResolver;
import app.ister.core.node.RemoteNodeClient;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.status.ActivityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Extracts one embedded subtitle stream to an SRT in the owning node's cache directory.
 *
 * <p>Three stages, deliberately not one transaction: a short read to build an immutable job, the
 * extraction (ffmpeg, mkvextract, subtile-ocr — minutes, no database session), and a short write.
 * A helper node runs the same code for another node's file: it reads the source through the
 * owner's download URL, writes the SRT to its own tmp directory, uploads it into the owner's
 * cache directory and records the owner-local path on the row, so the owner (and any other
 * node) resolves it exactly as if it had extracted the file itself.
 */
@Slf4j
@Component
public class SubtitleExtractionProcessor {

    private final MediaFileRepository mediaFileRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final DirectoryRepository directoryRepository;
    private final SubtitleExtractor extractor;
    private final MediaFileInputResolver inputResolver;
    private final RemoteNodeClient remoteNodeClient;
    private final TransactionTemplate readOnlyTransaction;
    private final TransactionTemplate writeTransaction;

    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    @SuppressWarnings("java:S107") // wiring, one collaborator per concern
    public SubtitleExtractionProcessor(MediaFileRepository mediaFileRepository,
                                       MediaFileStreamRepository mediaFileStreamRepository,
                                       DirectoryRepository directoryRepository,
                                       SubtitleExtractor extractor,
                                       MediaFileInputResolver inputResolver,
                                       RemoteNodeClient remoteNodeClient,
                                       PlatformTransactionManager transactionManager) {
        this.mediaFileRepository = mediaFileRepository;
        this.mediaFileStreamRepository = mediaFileStreamRepository;
        this.directoryRepository = directoryRepository;
        this.extractor = extractor;
        this.inputResolver = inputResolver;
        this.remoteNodeClient = remoteNodeClient;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Everything the extraction needs, read in one transaction: plain values and detached
     * entities whose basic fields are all that is touched afterwards.
     *
     * @param ownerUrl       base URL of the node owning the file (upload target when remote)
     * @param ownerCachePath the owner's cache directory — where the SRT must end up and what the row records
     */
    record ExtractionJob(String input, boolean remote, String ownerUrl, Path ownerCachePath,
                         MediaFileEntity mediaFile, List<MediaFileStreamEntity> streams,
                         MediaFileStreamEntity stream, int subIdx) {
    }

    public void process(UUID mediaFileId, UUID subtitleStreamId) {
        ExtractionJob job = readOnlyTransaction.execute(_ -> loadJob(mediaFileId, subtitleStreamId));
        if (job == null) {
            return;
        }
        ActivityContext.subject(Path.of(job.mediaFile().getPath()).getFileName().toString());
        // Per stream, not per file: the file's streams are extracted concurrently (listener
        // concurrency) and each one cleans up after itself, so a shared directory would be
        // deleted from under a sibling still running mkvextract or waiting to upload.
        Path srtDir = job.remote()
                ? Path.of(tmpDir, "subtitles", mediaFileId.toString(), subtitleStreamId.toString())
                : job.ownerCachePath();
        Optional<SubtitleExtractor.ExtractedSubtitle> extracted;
        try {
            Files.createDirectories(srtDir);
            extracted = extractor.extractOne(job.input(), mediaFileId, job.streams(), job.stream(), job.subIdx(), srtDir, dirOfFFmpeg);
            if (job.remote() && extracted.isPresent()) {
                remoteNodeClient.uploadToCache(job.ownerUrl(), extracted.get().srtFile());
            }
        } catch (IOException e) {
            // Network / disk trouble, not a tool failure: leave the row unflagged so the retry
            // (and later the scanner backfill) has another go.
            throw new EventHandlingException("Subtitle extraction of " + mediaFileId + " stream " + subtitleStreamId + " failed", e);
        } finally {
            if (job.remote()) {
                deleteQuietly(srtDir);
            }
        }
        boolean failed = Boolean.TRUE.equals(job.stream().getExtractionFailed());
        writeTransaction.executeWithoutResult(_ -> store(job, extracted.orElse(null), failed));
    }

    private ExtractionJob loadJob(UUID mediaFileId, UUID subtitleStreamId) {
        MediaFileStreamEntity stream = mediaFileStreamRepository.findById(subtitleStreamId).orElse(null);
        if (stream == null || stream.getCodecType() != StreamCodecType.SUBTITLE) {
            log.debug("Subtitle stream {} is gone or not a subtitle, nothing to extract", subtitleStreamId);
            return null;
        }
        if (Boolean.TRUE.equals(stream.getExtractionFailed())) {
            log.debug("Subtitle stream {} already failed extraction, skipping", subtitleStreamId);
            return null;
        }
        MediaFileEntity mediaFile = mediaFileRepository.findById(mediaFileId).orElse(null);
        if (mediaFile == null) {
            return null;
        }
        List<MediaFileStreamEntity> streams = mediaFile.getMediaFileStreamEntity().stream()
                .sorted(Comparator.comparingInt(MediaFileStreamEntity::getStreamIndex))
                .toList();
        boolean alreadyExtracted = streams.stream().anyMatch(s -> s.getCodecType() == StreamCodecType.EXTERNAL_SUBTITLE
                && s.getStreamIndex() == stream.getStreamIndex());
        if (alreadyExtracted) {
            log.debug("Subtitle stream {} of {} already has an extracted SRT row", subtitleStreamId, mediaFileId);
            return null;
        }
        // ffmpeg addresses subtitle streams by their rank among the subtitle streams (0:s:N).
        int subIdx = (int) streams.stream()
                .filter(s -> s.getCodecType() == StreamCodecType.SUBTITLE && s.getStreamIndex() < stream.getStreamIndex())
                .count();
        DirectoryEntity ownerCache = directoryRepository
                .findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, inputResolver.owner(mediaFile))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Owning node of " + mediaFileId + " has no cache directory"));
        return new ExtractionJob(inputResolver.resolve(mediaFile), inputResolver.isRemote(mediaFile),
                inputResolver.owner(mediaFile).getUrl(), Path.of(ownerCache.getPath()),
                mediaFile, streams, stream, subIdx);
    }

    private void store(ExtractionJob job, SubtitleExtractor.ExtractedSubtitle extracted, boolean failed) {
        // A re-analysis in the meantime rewrites the stream rows: then this result belongs to
        // rows that no longer exist, and the new rows will fire their own events.
        MediaFileStreamEntity source = mediaFileStreamRepository.findById(job.stream().getId()).orElse(null);
        if (source == null) {
            log.debug("Subtitle stream {} was rewritten during extraction, dropping the result", job.stream().getId());
            return;
        }
        if (extracted == null) {
            if (failed) {
                source.setExtractionFailed(true);
                mediaFileStreamRepository.save(source);
            }
            return;
        }
        Path recordedPath = job.ownerCachePath().resolve(extracted.srtFile().getFileName());
        MediaFileEntity mediaFile = source.getMediaFileEntity();
        if (mediaFileStreamRepository.existsByMediaFileEntityAndStreamIndexAndPath(mediaFile, source.getStreamIndex(), recordedPath.toString())) {
            return;
        }
        mediaFileStreamRepository.save(SubtitleExtractor.toEntity(mediaFile, source, extracted, recordedPath));
        log.info("Stored extracted subtitle {} for {}{}", recordedPath.getFileName(), mediaFile.getPath(),
                job.remote() ? " (uploaded to " + job.ownerUrl() + ")" : "");
    }

    private static void deleteQuietly(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException _) {
                            // best effort
                        }
                    });
                }
            }
        } catch (IOException e) {
            log.warn("Could not clean up {}: {}", dir, e.getMessage());
        }
    }
}
