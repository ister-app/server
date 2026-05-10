package app.ister.disk.scanner;

import app.ister.disk.scanner.enums.DirType;
import app.ister.disk.scanner.enums.FileType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a path relative to a music library root.
 *
 * Expected structure:
 *   {libraryRoot}/{Artist Name}/
 *   {libraryRoot}/{Artist Name}/{Album Name (optional year)}/
 *   {libraryRoot}/{Artist Name}/{Album Name (optional year)}/NN - Track Title.flac
 *   {libraryRoot}/{Artist Name}/artist.nfo
 *   {libraryRoot}/{Artist Name}/artist.jpg
 *   {libraryRoot}/{Artist Name}/{Album Name}/album.nfo
 *   {libraryRoot}/{Artist Name}/{Album Name}/cover.jpg
 */
@Getter
@Slf4j
public class MusicPathObject {

    private static final String REGEX_ALBUM_YEAR = "^(.*?)\\s*\\((\\d{4})\\)\\s*$";
    private static final String REGEX_TRACK_NUMBER = "^(\\d+)(?:[-./\\s].*)?$";
    private static final String REGEX_DISC_TRACK = "^(\\d+)[-./](\\d+)(?:[-./\\s].*)?$";
    private static final List<String> AUDIO_EXTENSIONS = List.of("mp3", "flac", "aac", "opus", "ogg", "wav", "m4a", "wma");
    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png");
    private static final List<String> ARTIST_IMAGE_NAMES = List.of("artist", "folder", "background", "thumb");
    private static final List<String> ALBUM_IMAGE_NAMES = List.of("cover", "folder");
    private static final List<String> FORMAT_SUFFIXES = List.of(
            " FLAC", " MP3", " AAC", " WAV", " OGG", " OPUS", " WMA", " M4A",
            " (FLAC)", " (MP3)", " (AAC)", " (WAV)", " [FLAC]", " [MP3]", " [AAC]", " [WAV]");

    private String artistName;
    private String albumName;
    private int albumYear;
    private int trackNumber;
    private int discNumber = 1;
    private DirType dirType = DirType.NONE;
    private FileType fileType = FileType.NONE;
    private boolean flatAlbumStructure = false;

    public MusicPathObject(String libraryRootPath, String currentPath) {
        String root = libraryRootPath.endsWith("/") ? libraryRootPath : libraryRootPath + "/";
        if (!currentPath.startsWith(root)) {
            return;
        }
        String relative = currentPath.substring(root.length());
        if (relative.isEmpty()) {
            return;
        }
        String[] parts = relative.split("/", -1);
        if (parts.length == 1) {
            parseOneLevel(parts);
        } else if (parts.length == 2) {
            parseTwoLevels(parts);
        } else if (parts.length >= 3) {
            parseThreePlusLevels(parts);
        }
    }

    private void parseOneLevel(String[] parts) {
        if (!hasExtension(parts[0])) {
            artistName = parts[0];
            dirType = DirType.ARTIST;
        }
    }

    private void parseTwoLevels(String[] parts) {
        artistName = parts[0];
        String item = parts[1];
        if (!hasExtension(item)) {
            albumName = parseAlbumName(item, artistName);
            albumYear = parseAlbumYear(item);
            dirType = DirType.ALBUM;
        } else {
            dirType = DirType.ARTIST;
            setFileTypeForArtistFile(item);
            String itemNameWithoutExt = removeExtension(item).toLowerCase();
            boolean isAlbumImage = fileType == FileType.IMAGE
                    && ALBUM_IMAGE_NAMES.stream().anyMatch(itemNameWithoutExt::contains);
            if (fileType == FileType.AUDIO || isAlbumImage) {
                albumName = parseAlbumName(parts[0], null);
                albumYear = parseAlbumYear(parts[0]);
                flatAlbumStructure = true;
            }
        }
    }

    private void parseThreePlusLevels(String[] parts) {
        artistName = parts[0];
        String albumPart = parts[1];
        albumName = parseAlbumName(albumPart, artistName);
        albumYear = parseAlbumYear(albumPart);
        dirType = DirType.ALBUM;
        setFileTypeForAlbumFile(parts[parts.length - 1]);
    }

    private void setFileTypeForArtistFile(String filename) {
        String ext = getExtension(filename).toLowerCase();
        String nameWithoutExt = removeExtension(filename).toLowerCase();
        if (IMAGE_EXTENSIONS.contains(ext) && (ARTIST_IMAGE_NAMES.stream().anyMatch(nameWithoutExt::contains)
                || ALBUM_IMAGE_NAMES.stream().anyMatch(nameWithoutExt::contains))) {
            fileType = FileType.IMAGE;
        } else if ("nfo".equals(ext) && "artist".equals(nameWithoutExt)) {
            fileType = FileType.NFO;
        } else if (AUDIO_EXTENSIONS.contains(ext)) {
            fileType = FileType.AUDIO;
            parseTrackInfo(filename);
        }
    }

    private void setFileTypeForAlbumFile(String filename) {
        String ext = getExtension(filename).toLowerCase();
        String nameWithoutExt = removeExtension(filename).toLowerCase();
        if (AUDIO_EXTENSIONS.contains(ext)) {
            fileType = FileType.AUDIO;
            parseTrackInfo(filename);
        } else if (IMAGE_EXTENSIONS.contains(ext) && ALBUM_IMAGE_NAMES.stream().anyMatch(nameWithoutExt::contains)) {
            fileType = FileType.IMAGE;
        } else if ("nfo".equals(ext) && "album".equals(nameWithoutExt)) {
            fileType = FileType.NFO;
        }
    }

    private void parseTrackInfo(String filename) {
        String nameWithoutExt = removeExtension(filename);
        // Try disc/track format: "1/01 - Title" or "1-01 - Title"
        Optional<MatchResult> discTrack = regex(REGEX_DISC_TRACK, nameWithoutExt);
        if (discTrack.isPresent()) {
            discNumber = Integer.parseInt(discTrack.get().group(1));
            trackNumber = Integer.parseInt(discTrack.get().group(2));
            return;
        }
        // Try plain track number at start: "01 - Title" or "01. Title"
        Optional<MatchResult> track = regex(REGEX_TRACK_NUMBER, nameWithoutExt);
        if (track.isPresent()) {
            trackNumber = Integer.parseInt(track.get().group(1));
            return;
        }
        // Fallback: scan all " - " segments for a number (handles "Artist - 20 - Title" format)
        for (String segment : nameWithoutExt.split(" - ")) {
            Optional<MatchResult> segTrack = regex(REGEX_TRACK_NUMBER, segment.trim());
            if (segTrack.isPresent()) {
                int candidate = Integer.parseInt(segTrack.get().group(1));
                if (candidate > 0 && candidate < 1000) {
                    trackNumber = candidate;
                    return;
                }
            }
        }
        // trackNumber stays 0 — will be corrected from tags in HandleAudioFileFound
    }

    private String parseAlbumName(String dirName, String artistNameToStrip) {
        // Strip year suffix first to get clean base name
        Optional<MatchResult> match = regex(REGEX_ALBUM_YEAR, dirName);
        String base = match.map(m -> m.group(1).trim()).orElse(dirName.trim());

        // Strip known format suffixes (case-insensitive)
        for (String suffix : FORMAT_SUFFIXES) {
            if (base.toUpperCase().endsWith(suffix.toUpperCase())) {
                base = base.substring(0, base.length() - suffix.length()).trim();
                break;
            }
        }

        // Strip leading "ArtistName - " prefix if present
        if (artistNameToStrip != null) {
            String prefix = artistNameToStrip + " - ";
            if (base.startsWith(prefix)) {
                base = base.substring(prefix.length()).trim();
            }
        }

        return base.isEmpty() ? dirName.trim() : base;
    }

    private int parseAlbumYear(String dirName) {
        Optional<MatchResult> match = regex(REGEX_ALBUM_YEAR, dirName);
        return match.map(m -> Integer.parseInt(m.group(2))).orElse(0);
    }

    private boolean hasExtension(String name) {
        return name.contains(".") && !name.startsWith(".");
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "";
    }

    private String removeExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(0, dot) : filename;
    }

    private Optional<MatchResult> regex(String regex, String input) {
        Matcher matcher = Pattern.compile(regex).matcher(input);
        return matcher.results().findFirst();
    }
}
