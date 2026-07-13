package app.ister.disk.scanner.scanners;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BaseEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import app.ister.core.utils.Jaffree;
import app.ister.disk.scanner.BookPathObject;
import app.ister.disk.scanner.MusicPathObject;
import app.ister.disk.scanner.PathObject;
import app.ister.disk.scanner.enums.DirType;
import app.ister.disk.scanner.enums.FileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
@Slf4j
@Transactional(propagation = Propagation.REQUIRES_NEW)
@RequiredArgsConstructor
public class ImageScanner implements Scanner {
    private static final List<String> BACKGROUND_FILE_NAMES = List.of("background", "thumb");
    private static final List<String> COVER_FILE_NAMES = List.of("cover");
    private static final List<String> AUDIO_EXTENSIONS = List.of("mp3", "flac", "aac", "opus", "ogg", "wav", "m4a", "wma");
    private final ScannerHelperService scannerHelperService;
    private final ImageRepository imageRepository;
    private final MediaFileRepository mediaFileRepository;
    private final MessageSender messageSender;
    private final Jaffree jaffree;

    @Override
    public boolean analyzable(Path path, boolean isRegularFile, long size) {
        return isRegularFile && new PathObject(path.toString()).getFileType().equals(FileType.IMAGE);
    }

    public boolean analyzable(Path path, boolean isRegularFile, long size, DirectoryEntity directoryEntity) {
        if (!isRegularFile) {
            return false;
        }
        if (isMusicLibrary(directoryEntity)) {
            MusicPathObject musicPath = new MusicPathObject(directoryEntity.getPath(), path.toString());
            return musicPath.getFileType().equals(FileType.IMAGE);
        }
        if (isBookLibrary(directoryEntity)) {
            BookPathObject bookPath = new BookPathObject(directoryEntity.getPath(), path.toString());
            return bookPath.getFileType().equals(FileType.IMAGE);
        }
        return analyzable(path, isRegularFile, size);
    }

    @Override
    public Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path path, boolean isRegularFile, long size) {
        if (imageRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString()).isPresent()) {
            return Optional.empty();
        }
        ImageType imageType = getImageType(path);
        if (imageType.equals(ImageType.UNKNOWN)) {
            return Optional.empty();
        }
        var imageEntity = ImageEntity.builder()
                .directoryEntityId(directoryEntity.getId())
                .sourceUri("file://" + path)
                .path(path.toString())
                .type(imageType);

        // Only run the video-path linker for non-music/non-book libraries. Otherwise the video
        // PathObject parser classifies music folder names (e.g. "Qmusic Top 500 ...") as a
        // SHOW/MOVIE and getOrCreateShow spawns an orphan show entity inside the MUSIC library.
        if (isMusicLibrary(directoryEntity)) {
            linkToMusicLibraryEntity(imageEntity, directoryEntity, path);
        } else if (isBookLibrary(directoryEntity)) {
            linkToBookLibraryEntity(imageEntity, directoryEntity, path);
        } else {
            linkToVideoLibraryEntity(imageEntity, new PathObject(path.toString()), directoryEntity);
        }

        ImageEntity build = imageEntity.build();
        imageRepository.save(build);
        messageSender.sendImageFound(ImageFoundData.fromImageEntity(build), directoryEntity.getName());
        return Optional.of(build);
    }

    private void linkToVideoLibraryEntity(ImageEntity.ImageEntityBuilder<?, ?> imageEntity,
                                           PathObject pathObject, DirectoryEntity directoryEntity) {
        if (pathObject.getDirType().equals(DirType.SHOW)) {
            imageEntity.showEntity(scannerHelperService.getOrCreateShow(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear()));
        } else if (pathObject.getDirType().equals(DirType.SEASON)) {
            imageEntity.seasonEntity(scannerHelperService.getOrCreateSeason(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear(), pathObject.getSeason()));
        } else if (pathObject.getDirType().equals(DirType.EPISODE)) {
            imageEntity.episodeEntity(scannerHelperService.getOrCreateEpisode(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear(), pathObject.getSeason(), pathObject.getEpisode()));
        } else if (pathObject.getDirType().equals(DirType.MOVIE)) {
            imageEntity.movieEntity(scannerHelperService.getOrCreateMovie(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear()));
        }
    }

    private boolean isMusicLibrary(DirectoryEntity directoryEntity) {
        return directoryEntity.getLibraryEntity() != null
                && directoryEntity.getLibraryEntity().getLibraryType() == LibraryType.MUSIC;
    }

    private boolean isBookLibrary(DirectoryEntity directoryEntity) {
        return directoryEntity.getLibraryEntity() != null
                && directoryEntity.getLibraryEntity().getLibraryType() == LibraryType.BOOK;
    }

    private void linkToBookLibraryEntity(ImageEntity.ImageEntityBuilder<?, ?> imageEntity,
                                          DirectoryEntity directoryEntity, Path path) {
        BookPathObject bookPath = new BookPathObject(directoryEntity.getPath(), path.toString());
        if (bookPath.getDirType() == DirType.ARTIST) {
            imageEntity.personEntity(scannerHelperService.getOrCreatePerson(directoryEntity.getLibraryEntity(), bookPath.getAuthorName(), bookPath.getAuthorYear()));
        } else if (bookPath.getDirType() == DirType.ALBUM) {
            var author = scannerHelperService.getOrCreatePerson(directoryEntity.getLibraryEntity(), bookPath.getAuthorName(), bookPath.getAuthorYear());
            imageEntity.bookEntity(scannerHelperService.getOrCreateBook(directoryEntity.getLibraryEntity(), author, bookPath.getBookName(), bookPath.getBookYear()));
        }
    }

    private void linkToMusicLibraryEntity(ImageEntity.ImageEntityBuilder<?, ?> imageEntity,
                                           DirectoryEntity directoryEntity, Path path) {
        MusicPathObject musicPath = new MusicPathObject(directoryEntity.getPath(), path.toString());
        if (musicPath.getDirType() == DirType.ARTIST && !musicPath.isFlatAlbumStructure()) {
            imageEntity.personEntity(scannerHelperService.getOrCreatePerson(directoryEntity.getLibraryEntity(), musicPath.getArtistName(), musicPath.getArtistYear()));
            return;
        }
        if (musicPath.getDirType() != DirType.ARTIST && musicPath.getDirType() != DirType.ALBUM) {
            return;
        }
        Optional<AlbumEntity> siblingAlbum = albumOfSiblingTracks(directoryEntity, path);
        if (siblingAlbum.isPresent()) {
            imageEntity.albumEntity(siblingAlbum.get());
            return;
        }
        String artistName = musicPath.isFlatAlbumStructure()
                ? readAlbumArtistFromDirectory(path.getParent(), musicPath.getArtistName())
                : musicPath.getArtistName();
        var artist = scannerHelperService.getOrCreatePerson(directoryEntity.getLibraryEntity(), artistName, musicPath.getArtistYear());
        imageEntity.albumEntity(scannerHelperService.getOrCreateAlbum(directoryEntity.getLibraryEntity(), artist, musicPath.getAlbumName(), musicPath.getAlbumYear()));
    }

    /**
     * The album a cover belongs to is the album its sibling audio files are already on — not the one
     * its own directory name spells out. Both are supposed to agree, but an album row created before
     * the name and year came from the path (rather than the audio tags) carries a name no scanner can
     * derive again, and matching on that name creates a fresh, track-less album per cover.jpg.
     *
     * <p>Empty when the audio in this directory has not been scanned yet; the caller then falls back
     * to the path-derived identity, which is what the audio scanner will use for the same files.
     */
    private Optional<AlbumEntity> albumOfSiblingTracks(DirectoryEntity directoryEntity, Path imagePath) {
        Path directory = imagePath.getParent();
        if (directory == null) {
            return Optional.empty();
        }
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(this::isAudioFile)
                    .map(audioFile -> mediaFileRepository.findByDirectoryEntityAndPath(directoryEntity, audioFile.toString()))
                    .flatMap(Optional::stream)
                    .map(MediaFileEntity::getTrackEntity)
                    .filter(Objects::nonNull)
                    .map(TrackEntity::getAlbumEntity)
                    .filter(Objects::nonNull)
                    .findFirst();
        } catch (IOException e) {
            log.warn("Could not list directory {}: {}", directory, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean isAudioFile(Path path) {
        String name = path.toString().toLowerCase();
        return AUDIO_EXTENSIONS.stream().anyMatch(ext -> name.endsWith("." + ext));
    }

    private String readAlbumArtistFromDirectory(Path directory, String fallback) {
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(this::isAudioFile)
                    .findFirst()
                    .map(audioFile -> {
                        try {
                            var format = jaffree.getFFPROBE().setShowFormat(true).setInput(audioFile.toString()).execute().getFormat();
                            if (format != null) {
                                String tag = format.getTag("album_artist");
                                if (tag == null) tag = format.getTag("ALBUM_ARTIST");
                                if (tag != null && !tag.isBlank()) return tag;
                            }
                        } catch (Exception e) {
                            log.warn("Could not read album_artist from {}: {}", audioFile, e.getMessage());
                        }
                        return fallback;
                    })
                    .orElse(fallback);
        } catch (IOException e) {
            log.warn("Could not list directory {}: {}", directory, e.getMessage());
            return fallback;
        }
    }

    private ImageType getImageType(Path path) {
        var filenameWithoutExt = removeExtension(path.getFileName().toString());
        if (BACKGROUND_FILE_NAMES.stream().anyMatch(filenameWithoutExt::contains)) {
            return ImageType.BACKGROUND;
        } else if (COVER_FILE_NAMES.stream().anyMatch(filenameWithoutExt::contains)) {
            return ImageType.COVER;
        } else {
            return ImageType.UNKNOWN;
        }
    }

    private String removeExtension(String string) {
        return string.replaceFirst("[.][^.]+$", "");
    }
}
