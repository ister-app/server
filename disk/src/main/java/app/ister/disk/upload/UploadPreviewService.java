package app.ister.disk.upload;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.LibraryType;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.repository.UploadFileRepository;
import app.ister.core.storage.LibraryWriteStoreResolver;
import app.ister.core.storage.PathStrings;
import app.ister.core.utils.LibraryPathValidator;
import app.ister.disk.scanner.BookPathObject;
import app.ister.disk.scanner.ComicPathObject;
import app.ister.disk.scanner.DirectoryPruner;
import app.ister.disk.scanner.MusicPathObject;
import app.ister.disk.scanner.PathObject;
import app.ister.disk.scanner.Scanners;
import app.ister.disk.scanner.enums.DirType;
import app.ister.disk.upload.UploadDtos.Entry;
import app.ister.disk.upload.UploadDtos.IgnoreReason;
import app.ister.disk.upload.UploadDtos.PlanRequest;
import app.ister.disk.upload.UploadDtos.PreviewEntry;
import app.ister.disk.upload.UploadDtos.PreviewResponse;
import app.ister.disk.upload.UploadDtos.PreviewRoot;
import app.ister.disk.upload.UploadDtos.PreviewStatus;
import app.ister.disk.upload.UploadDtos.Recognition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tells, without writing anything, where each file of an upload would land and what the scan would
 * make of it there. It asks the real scanners ({@link Scanners#analyzableBy}) and applies the real
 * pruning ({@link DirectoryPruner}), so a file reported as recognised is a file the library picks
 * up — the preview cannot drift from the scanner, because it is the scanner.
 *
 * <p>Works on paths only (plus the database for the "already there" check), so any node can answer
 * it; the node that can also see the storage adds a look at the storage itself.
 */
@Service
public class UploadPreviewService {

    private static final int IN_CLAUSE_BATCH = 1000;

    private final Scanners scanners;
    private final MediaFileRepository mediaFileRepository;
    private final ImageRepository imageRepository;
    private final OtherPathFileRepository otherPathFileRepository;
    private final UploadFileRepository uploadFileRepository;
    private final LibraryWriteStoreResolver writeStoreResolver;

    public UploadPreviewService(Scanners scanners, MediaFileRepository mediaFileRepository,
                                ImageRepository imageRepository, OtherPathFileRepository otherPathFileRepository,
                                UploadFileRepository uploadFileRepository,
                                LibraryWriteStoreResolver writeStoreResolver) {
        this.scanners = scanners;
        this.mediaFileRepository = mediaFileRepository;
        this.imageRepository = imageRepository;
        this.otherPathFileRepository = otherPathFileRepository;
        this.uploadFileRepository = uploadFileRepository;
        this.writeStoreResolver = writeStoreResolver;
    }

    /**
     * @throws IllegalArgumentException when the directory takes no uploads at all, or
     *                                  {@code targetParent}/{@code rootName} are not storable
     */
    @Transactional(readOnly = true)
    public PreviewResponse preview(DirectoryEntity directory, PlanRequest request) {
        LibraryType libraryType = requireUploadable(directory);
        String base = basePath(request);
        List<Entry> entries = request.entries() == null ? List.of() : request.entries();

        // Indexed, not keyed by the entry: two identical entries are equal records.
        String[] targets = new String[entries.size()];
        PreviewEntry[] verdicts = new PreviewEntry[entries.size()];

        // pass 1: where does everything go, and is that a storable, unique place
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            try {
                String relativeTarget = PathStrings.join(base, LibraryPathValidator.requireRelative(entry.relativePath()));
                String target = LibraryPathValidator.resolve(directory.getPath(), relativeTarget);
                if (seen.add(target)) {
                    targets[i] = target;
                } else {
                    verdicts[i] = verdict(entry, target, PreviewStatus.DUPLICATE, null, null, null);
                }
            } catch (IllegalArgumentException e) {
                verdicts[i] = verdict(entry, null, PreviewStatus.INVALID, null, e.getMessage(), null);
            }
        }

        // pass 2: would the scan pick it up
        List<String> wanted = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (targets[i] == null) {
                continue;
            }
            Entry entry = entries.get(i);
            Optional<String> prunedAt = firstPrunedAncestor(directory, targets[i]);
            if (prunedAt.isPresent()) {
                verdicts[i] = verdict(entry, targets[i], PreviewStatus.IGNORED, IgnoreReason.FOLDER_NOT_SCANNED,
                        relativeTo(directory, prunedAt.get()), null);
            } else if (scanners.analyzableBy(directory, targets[i], true, entry.size()).isEmpty()) {
                verdicts[i] = verdict(entry, targets[i], PreviewStatus.IGNORED, IgnoreReason.UNSUPPORTED_FILE, null, null);
            } else {
                wanted.add(targets[i]);
            }
        }

        // pass 3: is it already there, or on its way
        Set<String> existing = existingPaths(directory, wanted);
        Set<String> busy = new HashSet<>();
        batches(wanted).forEach(batch -> busy.addAll(uploadFileRepository.findActiveTargetPathsIn(batch)));
        long uploadBytes = 0;
        int uploadFiles = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (targets[i] == null || verdicts[i] != null) {
                continue;
            }
            Entry entry = entries.get(i);
            PreviewStatus status = status(targets[i], busy, existing);
            verdicts[i] = verdict(entry, targets[i], status, null, null, recognise(directory, libraryType, targets[i]));
            if (willUpload(status, request.overwrite())) {
                uploadBytes += entry.size();
                uploadFiles++;
            }
        }

        List<String> placed = java.util.Arrays.stream(targets).filter(java.util.Objects::nonNull).toList();
        return new PreviewResponse(directory.getId(), libraryType, roots(directory, libraryType, base, placed),
                List.of(verdicts), uploadBytes, uploadFiles);
    }

    private static PreviewStatus status(String target, Set<String> busy, Set<String> existing) {
        if (busy.contains(target)) {
            return PreviewStatus.BUSY;
        }
        return existing.contains(target) ? PreviewStatus.EXISTS : PreviewStatus.RECOGNISED;
    }

    /** Whether a session uploads a file with this verdict: the single rule preview totals and session creation share. */
    static boolean willUpload(PreviewStatus status, boolean overwrite) {
        return status == PreviewStatus.RECOGNISED || (status == PreviewStatus.EXISTS && overwrite);
    }

    static LibraryType requireUploadable(DirectoryEntity directory) {
        if (directory.getDirectoryType() != DirectoryType.LIBRARY || directory.getLibraryEntity() == null) {
            throw new IllegalArgumentException("Directory " + directory.getName() + " is not a library directory");
        }
        LibraryType libraryType = directory.getLibraryEntity().getLibraryType();
        if (libraryType == LibraryType.PODCAST) {
            throw new IllegalArgumentException("Podcast libraries are fed by their feeds, not by uploads");
        }
        return libraryType;
    }

    /** {@code targetParent/rootName}, either part optional; relative to the directory. */
    static String basePath(PlanRequest request) {
        String parent = LibraryPathValidator.optionalRelative(request.targetParent());
        if (request.rootName() == null || request.rootName().isBlank()) {
            return parent;
        }
        return PathStrings.join(parent, LibraryPathValidator.requireSegment(request.rootName().strip()));
    }

    private static PreviewEntry verdict(Entry entry, String target, PreviewStatus status, IgnoreReason reason,
                                        String detail, Recognition recognition) {
        return new PreviewEntry(entry.relativePath(), target, entry.size(), status, reason, detail, recognition);
    }

    private static String relativeTo(DirectoryEntity directory, String path) {
        String root = directory.getPath();
        String prefix = root.endsWith("/") ? root : root + "/";
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    /** The scan walks top-down and skips a whole subtree at the first folder it does not descend into. */
    private static Optional<String> firstPrunedAncestor(DirectoryEntity directory, String target) {
        List<String> ancestors = new ArrayList<>();
        for (String dir = PathStrings.parent(target);
             dir != null && PathStrings.isUnder(directory.getPath(), dir) && !dir.equals(directory.getPath());
             dir = PathStrings.parent(dir)) {
            ancestors.addFirst(dir);
        }
        return ancestors.stream().filter(dir -> !DirectoryPruner.shouldDescend(directory, dir)).findFirst();
    }

    private Set<String> existingPaths(DirectoryEntity directory, List<String> targets) {
        Set<String> existing = new HashSet<>();
        batches(targets).forEach(batch -> {
            existing.addAll(mediaFileRepository.findPathsByDirectoryEntityIdAndPathIn(directory.getId(), batch));
            existing.addAll(imageRepository.findPathsByDirectoryEntityIdAndPathIn(directory.getId(), batch));
            existing.addAll(otherPathFileRepository.findPathsByDirectoryEntityIdAndPathIn(directory.getId(), batch));
        });
        // The rows only know what was scanned. The node that holds the storage can see the rest.
        if (writeStoreResolver.canWrite(directory)) {
            var store = writeStoreResolver.storeFor(directory);
            targets.stream().filter(t -> !existing.contains(t)).filter(store::exists).forEach(existing::add);
        }
        return existing;
    }

    private static List<List<String>> batches(List<String> all) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < all.size(); i += IN_CLAUSE_BATCH) {
            batches.add(all.subList(i, Math.min(all.size(), i + IN_CLAUSE_BATCH)));
        }
        return batches;
    }

    private static Recognition recognise(DirectoryEntity directory, LibraryType libraryType, String target) {
        String root = directory.getPath();
        return switch (libraryType) {
            case MUSIC -> {
                MusicPathObject p = new MusicPathObject(root, target, false);
                yield new Recognition(p.getFileType().name(), null, null, null, null,
                        p.getArtistName(), p.getAlbumName(), p.getDiscNumber(), positive(p.getTrackNumber()),
                        null, null, null, null, null);
            }
            case BOOK -> {
                BookPathObject p = new BookPathObject(root, target, false);
                yield new Recognition(p.getFileType().name(), null, positive(p.getBookYear()), null, null,
                        null, null, null, null,
                        p.getAuthorName(), p.getBookName(), positive(p.getChapterNumber()), null, null);
            }
            case COMIC -> {
                ComicPathObject p = new ComicPathObject(root, target, false);
                yield new Recognition(p.getFileType().name(), p.getVolumeTitle(), positive(p.getStartYear()), null, null,
                        null, null, null, null, null, null, null, p.getSeriesName(), p.getVolumeNumber());
            }
            default -> {
                PathObject p = new PathObject(target);
                boolean episode = p.getDirType() == DirType.EPISODE;
                yield new Recognition(p.getFileType().name(), p.getName() == null ? null : p.getName().strip(),
                        positive(p.getYear()), episode ? p.getSeason() : null, episode ? p.getEpisodes() : null,
                        null, null, null, null, null, null, null, null, null);
            }
        };
    }

    private static Integer positive(int value) {
        return value > 0 ? value : null;
    }

    /**
     * The folders this upload creates or extends (the named root, or without one every first folder
     * on the way down), with the level the scan sees them at. This is what exposes a misplaced upload: an album dropped in the root of a music
     * directory shows up as ARTIST, a show folder without a year as NONE.
     */
    private List<PreviewRoot> roots(DirectoryEntity directory, LibraryType libraryType, String base,
                                    Iterable<String> targets) {
        Map<String, PreviewRoot> roots = new LinkedHashMap<>();
        for (String target : targets) {
            String rootPath = rootFolderOf(directory, base, target);
            if (rootPath == null || roots.containsKey(rootPath)) {
                continue;
            }
            roots.put(rootPath, new PreviewRoot(relativeTo(directory, rootPath), levelOf(directory, libraryType, rootPath),
                    existsAsFolder(directory, rootPath)));
        }
        return List.copyOf(roots.values());
    }

    /** The first folder below the directory root on the way to {@code target}; null for a file in the root itself. */
    private static String rootFolderOf(DirectoryEntity directory, String base, String target) {
        String relative = relativeTo(directory, target);
        int slash = relative.indexOf('/');
        if (slash < 0) {
            return null;
        }
        // Below targetParent/rootName the interesting folder is the deepest one the admin named.
        if (!base.isEmpty() && PathStrings.isUnder(PathStrings.join(directory.getPath(), base), target)) {
            return PathStrings.join(directory.getPath(), base);
        }
        return PathStrings.join(directory.getPath(), relative.substring(0, slash));
    }

    private static String levelOf(DirectoryEntity directory, LibraryType libraryType, String folder) {
        String root = directory.getPath();
        DirType dirType = switch (libraryType) {
            case MUSIC -> new MusicPathObject(root, folder, true).getDirType();
            case BOOK -> new BookPathObject(root, folder, true).getDirType();
            case COMIC -> new ComicPathObject(root, folder, true).getDirType();
            default -> new PathObject(folder).getDirType();
        };
        // The movie and show layouts share one parser, which calls every "Name (Year)" folder a show.
        if (libraryType == LibraryType.MOVIE && dirType == DirType.SHOW) {
            return DirType.MOVIE.name();
        }
        return dirType.name();
    }

    private boolean existsAsFolder(DirectoryEntity directory, String folder) {
        return writeStoreResolver.canWrite(directory) && !directory.isS3()
                && java.nio.file.Files.isDirectory(java.nio.file.Path.of(folder));
    }
}
