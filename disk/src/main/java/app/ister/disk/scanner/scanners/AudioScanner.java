package app.ister.disk.scanner.scanners;

import app.ister.core.entity.*;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.AudioFileFoundData;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import app.ister.core.utils.Jaffree;
import app.ister.disk.scanner.MusicPathObject;
import app.ister.disk.scanner.enums.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.util.Optional;

@Component
@Slf4j
@Transactional(propagation = Propagation.REQUIRES_NEW)
@RequiredArgsConstructor
public class AudioScanner implements Scanner {
    private final ScannerHelperService scannerHelperService;
    private final MediaFileRepository mediaFileRepository;
    private final MessageSender messageSender;
    private final Jaffree jaffree;

    @Override
    public boolean analyzable(Path path, boolean isRegularFile, long size) {
        return isRegularFile;
    }

    /**
     * Returns true if this path is an audio file in the given music library directory.
     */
    public boolean analyzable(Path path, boolean isRegularFile, DirectoryEntity directoryEntity) {
        if (!isRegularFile) {
            return false;
        }
        if (directoryEntity.getLibraryEntity() == null
                || directoryEntity.getLibraryEntity().getLibraryType() != LibraryType.MUSIC) {
            return false;
        }
        MusicPathObject musicPath = new MusicPathObject(directoryEntity.getPath(), path.toString());
        return musicPath.getFileType().equals(FileType.AUDIO);
    }

    @Override
    public Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path path, boolean isRegularFile, long size) {
        MusicPathObject musicPath = new MusicPathObject(directoryEntity.getPath(), path.toString());
        if (!musicPath.getFileType().equals(FileType.AUDIO)) {
            return Optional.empty();
        }

        LibraryEntity library = directoryEntity.getLibraryEntity();
        String artistName = musicPath.isFlatAlbumStructure()
                ? readAlbumArtistTag(path.toString(), musicPath.getArtistName())
                : musicPath.getArtistName();
        PersonEntity artist = scannerHelperService.getOrCreatePerson(library, artistName);
        AlbumEntity album = scannerHelperService.getOrCreateAlbum(library, artist, musicPath.getAlbumName(), musicPath.getAlbumYear());
        TrackEntity track = scannerHelperService.getOrCreateTrack(artist, album, musicPath.getTrackNumber(), musicPath.getDiscNumber());

        Optional<MediaFileEntity> existing = mediaFileRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString());
        final String directoryName = directoryEntity.getName();
        if (existing.isEmpty()) {
            MediaFileEntity entity = MediaFileEntity.builder()
                    .directoryEntityId(directoryEntity.getId())
                    .trackEntity(track)
                    .path(path.toString())
                    .size(size).build();
            mediaFileRepository.save(entity);
            sendAudioFileFoundAfterCommit(AudioFileFoundData.builder()
                    .eventType(EventType.AUDIO_FILE_FOUND)
                    .directoryEntityUUID(directoryEntity.getId())
                    .trackEntityUUID(track.getId())
                    .path(path.toString()).build(), directoryName);
        } else {
            MediaFileEntity existingFile = existing.get();
            if (existingFile.getTrackEntity() == null || !existingFile.getTrackEntity().getId().equals(track.getId())) {
                log.warn("Fixing wrong track association for {}: was track {} ({}), now track {} ({})",
                        path,
                        existingFile.getTrackEntity() != null ? existingFile.getTrackEntity().getNumber() : "null",
                        existingFile.getTrackEntity() != null ? existingFile.getTrackEntity().getId() : "null",
                        track.getNumber(), track.getId());
                existingFile.setTrackEntity(track);
                mediaFileRepository.save(existingFile);
                sendAudioFileFoundAfterCommit(AudioFileFoundData.fromMediaFileEntity(existingFile), directoryName);
            }
        }
        return Optional.of(track);
    }

    private void sendAudioFileFoundAfterCommit(AudioFileFoundData data, String directoryName) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messageSender.sendAudioFileFound(data, directoryName);
            }
        });
    }

    private String readAlbumArtistTag(String path, String fallback) {
        try {
            var format = jaffree.getFFPROBE().setShowFormat(true).setInput(path).execute().getFormat();
            if (format != null) {
                String tag = format.getTag("album_artist");
                if (tag == null) tag = format.getTag("ALBUM_ARTIST");
                if (tag != null && !tag.isBlank()) return tag;
            }
        } catch (Exception e) {
            log.warn("Could not read album_artist tag from {}: {}", path, e.getMessage());
        }
        return fallback;
    }
}
