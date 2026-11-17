package app.ister.disk.events.subtitlefilefound;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.enums.PathFileType;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.repository.OtherPathFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static app.ister.disk.events.subtitlefilefound.SubtitleFilePathParser.mediaFileAndSubtitleFileBelongTogether;

/**
 * Links a sidecar subtitle ({@code s01e01.nl.srt} next to {@code s01e01.mkv}) to its media file
 * as an {@code EXTERNAL_SUBTITLE} stream row.
 * <p>
 * The subtitle and the media file are scanned by parallel consumers, so neither side can count
 * on the other being there: the subtitle event may arrive before the media file row exists, and
 * the media file analysis replaces all stream rows of the file when it persists. Both sides
 * therefore link — {@code HandleSubtitleFileFound} for a subtitle that arrives after the media
 * file, {@code HandleMediaFileFound} for every sidecar already known when it persists — and the
 * link is an upsert, so doing it twice (or concurrently) is harmless.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SidecarSubtitleLinker {
    static final String CODEC_NAME = "subtitle srt";

    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final OtherPathFileRepository otherPathFileRepository;

    /** Links every sidecar subtitle of {@code directory} that belongs to {@code mediaFile}. */
    public void linkAllFor(DirectoryEntity directory, MediaFileEntity mediaFile) {
        String withoutExtension = SubtitleFilePathParser.removeExtension(mediaFile.getPath());
        otherPathFileRepository
                .findByDirectoryEntityIdAndPathFileTypeAndPathStartingWith(directory.getId(), PathFileType.SUBTITLE, withoutExtension)
                .stream()
                .filter(sidecar -> mediaFileAndSubtitleFileBelongTogether(mediaFile.getPath(), sidecar.getPath()))
                .forEach(sidecar -> link(directory, mediaFile, sidecar.getPath()));
    }

    /** Links the sidecar at {@code subtitlePath} to {@code mediaFile}; idempotent. */
    public void link(DirectoryEntity directory, MediaFileEntity mediaFile, String subtitlePath) {
        mediaFileStreamRepository.upsert(new MediaFileStreamRepository.StreamUpsert(
                CODEC_NAME, StreamCodecType.EXTERNAL_SUBTITLE.name(), 0,
                SubtitleFilePathParser.langCodeToIso3(subtitlePath),
                mediaFile.getId(), subtitlePath, 0, null, 0));
        mediaFileStreamRepository.findByMediaFileEntity_IdAndStreamIndexAndPath(mediaFile.getId(), 0, subtitlePath)
                .ifPresent(stream -> otherPathFileRepository.findByDirectoryEntityAndPath(directory, subtitlePath)
                        .ifPresent(sidecar -> {
                            sidecar.setMediaFileStreamEntity(stream);
                            otherPathFileRepository.save(sidecar);
                        }));
        log.debug("Linked sidecar subtitle {} to {}", subtitlePath, mediaFile.getPath());
    }
}
