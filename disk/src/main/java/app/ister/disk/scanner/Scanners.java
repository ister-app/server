package app.ister.disk.scanner;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.enums.LibraryType;
import app.ister.disk.scanner.scanners.AudioScanner;
import app.ister.disk.scanner.scanners.ComicScanner;
import app.ister.disk.scanner.scanners.EpubScanner;
import app.ister.disk.scanner.scanners.ImageScanner;
import app.ister.disk.scanner.scanners.MediaFileScanner;
import app.ister.disk.scanner.scanners.NfoScanner;
import app.ister.disk.scanner.scanners.Scanner;
import app.ister.disk.scanner.scanners.SubtitleScanner;

import java.util.List;

/**
 * The scanners, and the one place that knows which of them look at a library type and how each is
 * asked whether it wants a file. The scan walk, the {@code FILE_SCAN_REQUESTED} handler and the
 * upload preview all go through here, so "will this file be picked up" has a single answer.
 */
public record Scanners(
        MediaFileScanner mediaFile,
        ImageScanner image,
        NfoScanner nfo,
        SubtitleScanner subtitle,
        AudioScanner audio,
        EpubScanner epub,
        ComicScanner comic) {

    public List<Scanner> forLibrary(LibraryType libraryType) {
        if (libraryType == LibraryType.MUSIC) {
            return List.of(audio, image, nfo);
        }
        if (libraryType == LibraryType.BOOK) {
            return List.of(epub, audio, image, nfo);
        }
        if (libraryType == LibraryType.COMIC) {
            return List.of(comic, image);
        }
        return List.of(mediaFile, image, nfo, subtitle);
    }

    /** Music, book and comic paths are parsed relative to the library root, so their scanners need the directory. */
    public static boolean directoryScoped(LibraryType libraryType) {
        return libraryType == LibraryType.MUSIC || libraryType == LibraryType.BOOK || libraryType == LibraryType.COMIC;
    }

    public static LibraryType libraryTypeOf(DirectoryEntity directoryEntity) {
        return directoryEntity.getLibraryEntity() == null ? null : directoryEntity.getLibraryEntity().getLibraryType();
    }

    public boolean analyzable(Scanner scanner, DirectoryEntity directoryEntity, String path, boolean regularFile, long size) {
        if (!directoryScoped(libraryTypeOf(directoryEntity))) {
            return scanner.analyzable(path, regularFile, size);
        }
        return switch (scanner) {
            case AudioScanner s -> s.analyzable(path, regularFile, directoryEntity);
            case EpubScanner s -> s.analyzable(path, regularFile, directoryEntity);
            case ComicScanner s -> s.analyzable(path, regularFile, directoryEntity);
            case ImageScanner s -> s.analyzable(path, regularFile, size, directoryEntity);
            case NfoScanner s -> s.analyzable(path, regularFile, size, directoryEntity);
            default -> scanner.analyzable(path, regularFile, size);
        };
    }

    /** The scanners that will pick this file up; empty means the scan ignores it. */
    public List<Scanner> analyzableBy(DirectoryEntity directoryEntity, String path, boolean regularFile, long size) {
        return forLibrary(libraryTypeOf(directoryEntity)).stream()
                .filter(scanner -> analyzable(scanner, directoryEntity, path, regularFile, size))
                .toList();
    }
}
