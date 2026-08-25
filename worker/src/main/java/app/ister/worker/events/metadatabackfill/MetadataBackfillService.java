package app.ister.worker.events.metadatabackfill;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.LibraryType;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.SeriesRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.BookSeriesService;
import app.ister.worker.events.common.FoundEventDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The metadata backfill steps, each in its own transaction (dispatch steps read-only, the book
 * series heuristic in a write transaction). Separate bean from {@link MetadataBackfillHandle} so
 * the per-method {@code @Transactional} boundaries actually apply (no self-invocation).
 *
 * <p>Every step is only-missing: it re-dispatches {@code *_FOUND} events exclusively for items
 * that lack metadata, artwork or enrichment. No skip-if-complete guard exists on the movie/show/
 * episode handlers — none is needed, because every emitter already wants the fetch: the scanner
 * emits on create, this backfill only for missing items, and the force flow after deleting.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MetadataBackfillService {

    private final ShowRepository showRepository;
    private final EpisodeRepository episodeRepository;
    private final MovieRepository movieRepository;
    private final PersonRepository personRepository;
    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;
    private final BookRepository bookRepository;
    private final SeriesRepository seriesRepository;
    private final BookSeriesService bookSeriesService;
    private final MediaFileRepository mediaFileRepository;
    private final MetadataRepository metadataRepository;
    private final OtherPathFileRepository otherPathFileRepository;
    private final DirectoryRepository directoryRepository;
    private final FoundEventDispatcher dispatcher;

    /** Shows, episodes and movies missing metadata or TMDB enrichment (tmdbId null = pre-V45). */
    @Transactional(readOnly = true)
    public void dispatchMissingVideoMetadataEvents(UUID libraryId) {
        showRepository.findIdsOfShowsNeedingMetadata(libraryId).forEach(dispatcher::showFound);
        episodeRepository.findIdsOfEpisodesWithoutMetadata(libraryId).forEach(dispatcher::episodeFound);
        movieRepository.findIdsOfMoviesNeedingMetadata(libraryId).forEach(dispatcher::movieFound);
    }

    /**
     * Music artists and book authors are both persons; PERSON_FOUND picks the metadata source.
     * Deliberately unrouted: the enrichment handler (MusicBrainz/Open Library/Wikipedia) is the
     * worker's, which listens on the global queue. A node-scoped send would land on the disk
     * handler's queue instead, which only re-parses artist.nfo — no external lookup would happen.
     */
    @Transactional(readOnly = true)
    public void dispatchMissingPersonMetadataEvents(UUID libraryId) {
        Stream.of(LibraryType.MUSIC, LibraryType.BOOK)
                .flatMap(type -> personRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(type).stream())
                .filter(p -> inLibrary(libraryId, p.getLibraryEntity()))
                .forEach(p -> dispatcher.personFoundGlobal(p.getId()));
    }

    @Transactional(readOnly = true)
    public void dispatchMissingMusicMetadataEvents(UUID libraryId) {
        // Metadata-missing albums go to the disk handler of every node holding a directory of the
        // album's library (album.nfo / folder artwork re-parse), image-missing ones to the global
        // worker queue (MusicBrainz cover) — same routing as before, but emitted exactly once.
        Map<UUID, Set<String>> nodesByLibrary = libraryNodeNames();
        albumRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.MUSIC).stream()
                .filter(a -> inLibrary(libraryId, a.getLibraryEntity()))
                .forEach(a -> nodesByLibrary
                        .getOrDefault(a.getLibraryEntity().getId(), Set.of())
                        .forEach(nodeName -> dispatcher.albumFoundToNode(a.getId(), nodeName)));

        albumRepository.findByLibraryEntity_LibraryTypeAndImageEntitiesIsEmpty(LibraryType.MUSIC).stream()
                .filter(a -> inLibrary(libraryId, a.getLibraryEntity()))
                .forEach(a -> dispatcher.albumFoundGlobal(a.getId()));

        List<TrackEntity> tracksWithoutMetadata =
                trackRepository.findByAlbumEntity_LibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.MUSIC).stream()
                        .filter(t -> inLibrary(libraryId, t.getAlbumEntity().getLibraryEntity()))
                        .toList();
        Map<UUID, DirectoryEntity> directories = directoriesById();
        mediaFilesByOwner(tracksWithoutMetadata, TrackEntity::getId, mediaFileRepository::findByTrackEntityIdIn,
                m -> m.getTrackEntity() == null ? null : m.getTrackEntity().getId())
                .values().stream()
                .flatMap(Collection::stream)
                .filter(m -> m.getDirectoryEntityId() != null)
                .forEach(m -> Optional.ofNullable(directories.get(m.getDirectoryEntityId()))
                        .ifPresent(dir -> dispatcher.audioFileFound(m, dir.getName())));
    }

    /**
     * Backfill for books. A plain rescan skips epubs whose media file already exists, so this is
     * the only path that re-enriches existing books.
     *
     * <p>Books without an Open Library metadata row get their epubs re-parsed (EPUB_FILE_FOUND
     * writes the release date and ISBN, then chains BOOK_FOUND itself — dispatching BOOK_FOUND
     * directly would race the Open Library lookup against the ISBN being stored). Books without
     * any epub file (audiobook-only), books without a cover, series books with missing Wikidata
     * info and series-less books whose author has a series go straight to BOOK_FOUND.
     */
    @Transactional(readOnly = true)
    public void dispatchMissingBookMetadataEvents(UUID libraryId) {
        Map<UUID, DirectoryEntity> directories = directoriesById();
        Set<UUID> dispatched = new HashSet<>();

        List<BookEntity> withoutOpenLibrary =
                bookRepository.findBooksWithoutOpenLibraryMetadata(LibraryType.BOOK).stream()
                        .filter(b -> inLibrary(libraryId, b.getLibraryEntity()))
                        .toList();
        Map<UUID, List<MediaFileEntity>> epubsByBook = mediaFilesByOwner(withoutOpenLibrary,
                BookEntity::getId, mediaFileRepository::findByBookEntityIdIn,
                m -> m.getBookEntity() == null ? null : m.getBookEntity().getId());
        withoutOpenLibrary.forEach(book -> {
            dispatched.add(book.getId());
            List<MediaFileEntity> epubs = epubsByBook.getOrDefault(book.getId(), List.of()).stream()
                    .filter(m -> m.getDirectoryEntityId() != null)
                    .toList();
            if (epubs.isEmpty()) {
                dispatcher.bookFound(book.getId());
                return;
            }
            epubs.forEach(m -> Optional.ofNullable(directories.get(m.getDirectoryEntityId()))
                    .ifPresent(dir -> dispatcher.epubFileFound(dir, book.getId(), m)));
        });

        bookRepository.findByLibraryEntity_LibraryTypeAndImageEntitiesIsEmpty(LibraryType.BOOK).stream()
                .filter(b -> inLibrary(libraryId, b.getLibraryEntity()))
                .map(BookEntity::getId)
                .filter(dispatched::add)
                .forEach(dispatcher::bookFound);

        // Series books with an unknown position or unresolved original year retry the Wikidata
        // lookup (the BOOK_FOUND handler runs it after the Open Library part).
        bookRepository.findSeriesBooksMissingWikidataInfo(LibraryType.BOOK).stream()
                .filter(b -> inLibrary(libraryId, b.getLibraryEntity()))
                .map(BookEntity::getId)
                .filter(dispatched::add)
                .forEach(dispatcher::bookFound);

        // Series-less books whose author has a series retry Wikidata series discovery: theirs may
        // have run before the series existed, or failed against Wikidata at the time. Without this
        // an already-enriched book (Open Library row + cover) is never dispatched again and stays
        // out of its series forever.
        bookRepository.findSerieslessBooksOfAuthorsWithSeries(LibraryType.BOOK).stream()
                .filter(b -> inLibrary(libraryId, b.getLibraryEntity()))
                .map(BookEntity::getId)
                .filter(dispatched::add)
                .forEach(dispatcher::bookFound);
    }

    /**
     * The series prefix heuristic per author + orphan-series cleanup, in a write transaction of its
     * own. Runs once cluster-wide now (the backfill event is global), so two nodes no longer race
     * each other through the same series table.
     */
    @Transactional
    public void applyBookSeriesHeuristics(UUID libraryId) {
        bookRepository.findDistinctAuthors(LibraryType.BOOK, libraryId)
                .forEach(bookSeriesService::applyPrefixHeuristic);
        bookSeriesService.cleanupOrphanSeries();
    }

    /**
     * Books still missing a series or position re-parse their album.nfo: the nfo {@code <set>} and
     * the "{series} {N} - " review opening are the only local source for audiobook-only books
     * (no epub), and a plain rescan never re-fires NFO_FILE_FOUND for a known nfo file. Runs
     * after the prefix heuristic so a heuristic-assigned series gets its index filled in the
     * same pass. Fill-only in the handler, so re-dispatching is idempotent and stops matching
     * once series and index are set.
     */
    @Transactional(readOnly = true)
    public void dispatchBookSeriesNfoEvents(UUID libraryId) {
        Map<UUID, DirectoryEntity> directories = directoriesById();
        bookRepository.findBooksMissingSeriesInfo(LibraryType.BOOK, libraryId)
                .forEach(book -> sendNfoFileFoundForBook(book, directories));
    }

    /**
     * Backfill for comics: series without metadata retry the Wikipedia lookup, and volumes without
     * a cover get their cbz/pdf files re-parsed (which also re-extracts page counts and embedded
     * ComicInfo.xml). Epub volumes re-enter through EPUB_FILE_FOUND like books do.
     */
    @Transactional(readOnly = true)
    public void dispatchMissingComicMetadataEvents(UUID libraryId) {
        seriesRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.COMIC).stream()
                .filter(s -> inLibrary(libraryId, s.getLibraryEntity()))
                .forEach(series -> dispatcher.comicSeriesFound(series.getId()));

        Map<UUID, DirectoryEntity> directories = directoriesById();
        List<BookEntity> withoutCover =
                bookRepository.findByLibraryEntity_LibraryTypeAndImageEntitiesIsEmpty(LibraryType.COMIC).stream()
                        .filter(b -> inLibrary(libraryId, b.getLibraryEntity()))
                        .toList();
        Map<UUID, List<MediaFileEntity>> filesByVolume = mediaFilesByOwner(withoutCover,
                BookEntity::getId, mediaFileRepository::findByBookEntityIdIn,
                m -> m.getBookEntity() == null ? null : m.getBookEntity().getId());
        withoutCover.forEach(volume -> filesByVolume.getOrDefault(volume.getId(), List.of()).stream()
                .filter(m -> m.getDirectoryEntityId() != null)
                .forEach(m -> Optional.ofNullable(directories.get(m.getDirectoryEntityId()))
                        .ifPresent(dir -> dispatcher.comicVolumeFileFound(dir, volume.getId(), m))));
    }

    private void sendNfoFileFoundForBook(BookEntity book, Map<UUID, DirectoryEntity> directories) {
        metadataRepository.findByBookEntityId(book.getId()).stream()
                .filter(m -> m.getSourceUri() != null
                        && m.getSourceUri().startsWith("file://")
                        && m.getSourceUri().endsWith(".nfo"))
                .map(otherPathFileRepository::findByMetadataEntity)
                .flatMap(Optional::stream)
                .forEach(nfoFile -> Optional.ofNullable(directories.get(nfoFile.getDirectoryEntityId()))
                        .ifPresent(dir -> dispatcher.nfoFileFound(dir, nfoFile.getPath())));
    }

    private boolean inLibrary(UUID libraryId, LibraryEntity library) {
        return libraryId == null || (library != null && libraryId.equals(library.getId()));
    }

    /** All directories in one query — there are only a handful, and it kills the per-file lookups. */
    private Map<UUID, DirectoryEntity> directoriesById() {
        return StreamSupport.stream(directoryRepository.findAll().spliterator(), false)
                .collect(Collectors.toMap(DirectoryEntity::getId, Function.identity()));
    }

    /** Node names holding a LIBRARY directory, per library id. */
    private Map<UUID, Set<String>> libraryNodeNames() {
        return directoryRepository.findByDirectoryType(DirectoryType.LIBRARY).stream()
                .filter(dir -> dir.getLibraryEntity() != null)
                .collect(Collectors.groupingBy(dir -> dir.getLibraryEntity().getId(),
                        Collectors.mapping(dir -> dir.getNodeEntity().getName(), Collectors.toSet())));
    }

    /** Media files of many owners in one query, grouped back per owner id. */
    private <T> Map<UUID, List<MediaFileEntity>> mediaFilesByOwner(
            List<T> owners,
            Function<T, UUID> ownerId,
            Function<Collection<UUID>, List<MediaFileEntity>> batchQuery,
            Function<MediaFileEntity, UUID> fileOwnerId) {
        List<UUID> ids = owners.stream().map(ownerId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return batchQuery.apply(ids).stream()
                .filter(m -> fileOwnerId.apply(m) != null)
                .collect(Collectors.groupingBy(fileOwnerId));
    }
}
