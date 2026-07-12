package app.ister.disk.scanner.scanners;

import app.ister.core.entity.BaseEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.repository.ImageRepository;
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
        if (musicPath.getDirType() == DirType.ARTIST && musicPath.isFlatAlbumStructure()) {
            String artistName = readAlbumArtistFromDirectory(path.getParent(), musicPath.getArtistName());
            var artist = scannerHelperService.getOrCreatePerson(directoryEntity.getLibraryEntity(), artistName, musicPath.getArtistYear());
            imageEntity.albumEntity(scannerHelperService.getOrCreateAlbum(directoryEntity.getLibraryEntity(), artist, musicPath.getAlbumName(), musicPath.getAlbumYear()));
        } else if (musicPath.getDirType() == DirType.ARTIST) {
            imageEntity.personEntity(scannerHelperService.getOrCreatePerson(directoryEntity.getLibraryEntity(), musicPath.getArtistName(), musicPath.getArtistYear()));
        } else if (musicPath.getDirType() == DirType.ALBUM) {
            var artist = scannerHelperService.getOrCreatePerson(directoryEntity.getLibraryEntity(), musicPath.getArtistName(), musicPath.getArtistYear());
            imageEntity.albumEntity(scannerHelperService.getOrCreateAlbum(directoryEntity.getLibraryEntity(), artist, musicPath.getAlbumName(), musicPath.getAlbumYear()));
        }
    }

    private String readAlbumArtistFromDirectory(Path directory, String fallback) {
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(p -> AUDIO_EXTENSIONS.stream().anyMatch(ext -> p.toString().toLowerCase().endsWith("." + ext)))
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
