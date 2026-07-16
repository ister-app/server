package app.ister.disk.events.comicfilefound;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads cbz comic archives (plain zips of page images) with java.util.zip. Pages are the image
 * entries in natural sort order — numeric-aware, so "page2" sorts before "page10" — skipping
 * archive junk (__MACOSX/, dotfiles). ComicInfo.xml (any casing, any folder) provides embedded
 * metadata when present.
 */
@Component
@Slf4j
public class CbzParser {

    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp", "gif");
    private static final Pattern DIGIT_RUNS = Pattern.compile("(\\d+)|(\\D+)");
    private static final String COMIC_INFO = "comicinfo.xml";

    /** The page image entry names in reading order. */
    public List<String> pages(Path cbzPath) {
        try (ZipFile zipFile = new ZipFile(cbzPath.toFile())) {
            return zipFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(ZipEntry::getName)
                    .filter(CbzParser::isPageImage)
                    .sorted(CbzParser::naturalCompare)
                    .toList();
        } catch (IOException e) {
            log.warn("Could not read cbz {}: {}", cbzPath, e.getMessage());
            return List.of();
        }
    }

    /** The embedded ComicInfo.xml, when the archive carries one. */
    public Optional<ComicInfoXml> comicInfo(Path cbzPath) {
        try (ZipFile zipFile = new ZipFile(cbzPath.toFile())) {
            Optional<? extends ZipEntry> entry = zipFile.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().toLowerCase().endsWith(COMIC_INFO))
                    .findFirst();
            if (entry.isEmpty()) {
                return Optional.empty();
            }
            try (InputStream in = zipFile.getInputStream(entry.get())) {
                return ComicInfoXml.parse(in);
            }
        } catch (IOException e) {
            log.warn("Could not read ComicInfo.xml from {}: {}", cbzPath, e.getMessage());
            return Optional.empty();
        }
    }

    /** Extracts a zip entry (e.g. a page image) as bytes. */
    public Optional<byte[]> readEntry(Path cbzPath, String entryName) {
        try (ZipFile zipFile = new ZipFile(cbzPath.toFile())) {
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null) {
                return Optional.empty();
            }
            try (InputStream in = zipFile.getInputStream(entry)) {
                return Optional.of(in.readAllBytes());
            }
        } catch (IOException e) {
            log.warn("Could not read entry {} from cbz {}: {}", entryName, cbzPath, e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isPageImage(String name) {
        String lower = name.toLowerCase();
        if (lower.startsWith("__macosx/") || Path.of(name).getFileName().toString().startsWith(".")) {
            return false;
        }
        int dot = lower.lastIndexOf('.');
        return dot >= 0 && IMAGE_EXTENSIONS.contains(lower.substring(dot + 1));
    }

    /** Numeric-aware comparison: "page2.jpg" < "page10.jpg". */
    static int naturalCompare(String a, String b) {
        Matcher ma = DIGIT_RUNS.matcher(a);
        Matcher mb = DIGIT_RUNS.matcher(b);
        while (ma.find() && mb.find()) {
            String da = ma.group(1);
            String db = mb.group(1);
            int result;
            if (da != null && db != null) {
                result = compareDigits(da, db);
            } else {
                result = ma.group().compareToIgnoreCase(mb.group());
            }
            if (result != 0) {
                return result;
            }
        }
        return Comparator.<String>naturalOrder().compare(a, b);
    }

    /** Numeric string comparison without overflow: strip leading zeros, longer run is bigger. */
    private static int compareDigits(String a, String b) {
        String sa = a.replaceFirst("^0+(?=.)", "");
        String sb = b.replaceFirst("^0+(?=.)", "");
        return sa.length() != sb.length() ? Integer.compare(sa.length(), sb.length()) : sa.compareTo(sb);
    }
}
