package app.ister.worker.events.analyzelibraryrequested;

import app.ister.core.Handle;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.eventdata.AlbumFoundData;
import app.ister.core.eventdata.AnalyzeLibraryRequestedData;
import app.ister.core.eventdata.BookFoundData;
import app.ister.core.eventdata.ComicFileFoundData;
import app.ister.core.eventdata.ComicSeriesFoundData;
import app.ister.core.eventdata.EpubFileFoundData;
import app.ister.core.eventdata.PersonFoundData;
import app.ister.core.eventdata.AudioFileFoundData;
import app.ister.core.eventdata.EpisodeFoundData;
import app.ister.core.eventdata.MovieFoundData;
import app.ister.core.eventdata.NfoFileFoundData;
import app.ister.core.eventdata.ShowFoundData;
import app.ister.core.eventdata.UpdateImagesRequestedData;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.repository.SeriesRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.BookSeriesService;
import app.ister.core.service.MessageSender;
import app.ister.core.service.NodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handles {@link EventType#ANALYZE_LIBRARY_REQUEST} by:
 * 1. Publishing an update-images event for this node.
 * 2. Publishing "metadata-missing" events for shows, episodes, movies, persons, albums, tracks
 *    and books.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class AnalyzeLibraryRequestedHandle implements Handle<AnalyzeLibraryRequestedData> {

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
    private final MessageSender messageSender;
    private final NodeService nodeService;
    private final DirectoryRepository directoryRepository;

    @RabbitListener(queues = "#{@queueNamingConfig.getAnalyzeLibraryRequestedQueue()}")
    @Override
    public void listener(AnalyzeLibraryRequestedData data) {
        Handle.super.listener(data);
    }

    @Override
    public EventType handles() {
        return EventType.ANALYZE_LIBRARY_REQUEST;
    }

    @Override
    public void handle(AnalyzeLibraryRequestedData data) {
        var nodeEntity = nodeService.getOrCreateNodeEntityForThisNode();
        // Every directory of this node, CACHE included: downloaded artwork lives there and makes up
        // the bulk of the images. The sweep is scoped per directory, so a directory left out here
        // never gets its blur-hashes computed at all.
        directoryRepository.findByNodeEntity(nodeEntity).forEach(dir ->
                messageSender.sendUpdateImagesRequested(
                        UpdateImagesRequestedData.builder()
                                .eventType(EventType.UPDATE_IMAGES_REQUESTED)
                                .directoryEntityId(dir.getId())
                                .directoryName(dir.getName())
                                .build(),
                        dir.getName()));
        dispatchMissingMetadataEvents(nodeEntity.getName());
        dispatchMissingPersonMetadataEvents();
        dispatchMissingMusicMetadataEvents(nodeEntity.getName());
        dispatchMissingBookMetadataEvents();
        dispatchMissingComicMetadataEvents();
    }

    /**
     * Backfill for comics: series without metadata retry the Wikipedia lookup, and volumes without
     * a cover get their cbz/pdf files re-parsed (which also re-extracts page counts and embedded
     * ComicInfo.xml). Epub volumes re-enter through EPUB_FILE_FOUND like books do.
     */
    private void dispatchMissingComicMetadataEvents() {
        seriesRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.COMIC)
                .forEach(series -> messageSender.sendComicSeriesFound(ComicSeriesFoundData.builder()
                        .eventType(EventType.COMIC_SERIES_FOUND)
                        .seriesId(series.getId())
                        .build()));

        bookRepository.findByLibraryEntity_LibraryTypeAndImageEntitiesIsEmpty(LibraryType.COMIC)
                .forEach(volume -> mediaFileRepository.findByBookEntityId(volume.getId()).stream()
                        .filter(m -> m.getDirectoryEntityId() != null)
                        .forEach(m -> directoryRepository.findById(m.getDirectoryEntityId())
                                .ifPresent(dir -> sendComicVolumeFileEvent(dir, volume.getId(), m))));
    }

    private void sendComicVolumeFileEvent(DirectoryEntity dir, UUID volumeId, MediaFileEntity mediaFile) {
        if (mediaFile.getPath().toLowerCase().endsWith(".epub")) {
            messageSender.sendEpubFileFound(EpubFileFoundData.builder()
                    .eventType(EventType.EPUB_FILE_FOUND)
                    .directoryEntityUUID(dir.getId())
                    .bookEntityUUID(volumeId)
                    .mediaFileEntityUUID(mediaFile.getId())
                    .path(mediaFile.getPath())
                    .build(), dir.getName());
        } else {
            messageSender.sendComicFileFound(ComicFileFoundData.builder()
                    .eventType(EventType.COMIC_FILE_FOUND)
                    .directoryEntityUUID(dir.getId())
                    .bookEntityUUID(volumeId)
                    .mediaFileEntityUUID(mediaFile.getId())
                    .path(mediaFile.getPath())
                    .build(), dir.getName());
        }
    }

    /**
     * Backfill for books. A plain rescan skips epubs whose media file already exists, so this is
     * the only path that re-enriches existing books.
     *
     * <p>Books without an Open Library metadata row get their epubs re-parsed (EPUB_FILE_FOUND
     * writes the release date and ISBN, then chains BOOK_FOUND itself — dispatching BOOK_FOUND
     * directly would race the Open Library lookup against the ISBN being stored). Books without
     * any epub file (audiobook-only), books without a cover, series books with missing Wikidata
     * info and series-less books whose author has a series go straight to BOOK_FOUND. Then
     * the series heuristic runs per author, so pre-existing libraries converge without a rescan.
     * Finally books still missing a series or series position get their album.nfo re-dispatched
     * (NFO_FILE_FOUND), the local fallback for audiobook-only books.
     */
    private void dispatchMissingBookMetadataEvents() {
        Set<UUID> dispatched = new HashSet<>();
        bookRepository.findBooksWithoutOpenLibraryMetadata(LibraryType.BOOK).forEach(book -> {
            dispatched.add(book.getId());
            List<MediaFileEntity> epubs = mediaFileRepository.findByBookEntityId(book.getId()).stream()
                    .filter(m -> m.getDirectoryEntityId() != null)
                    .toList();
            if (epubs.isEmpty()) {
                sendBookFound(book.getId());
                return;
            }
            epubs.forEach(m -> directoryRepository.findById(m.getDirectoryEntityId())
                    .ifPresent(dir -> messageSender.sendEpubFileFound(
                            EpubFileFoundData.builder()
                                    .eventType(EventType.EPUB_FILE_FOUND)
                                    .directoryEntityUUID(dir.getId())
                                    .bookEntityUUID(book.getId())
                                    .mediaFileEntityUUID(m.getId())
                                    .path(m.getPath())
                                    .build(),
                            dir.getName())));
        });

        bookRepository.findByLibraryEntity_LibraryTypeAndImageEntitiesIsEmpty(LibraryType.BOOK).stream()
                .map(BookEntity::getId)
                .filter(dispatched::add)
                .forEach(this::sendBookFound);

        // Series books with an unknown position or unresolved original year retry the Wikidata
        // lookup (the BOOK_FOUND handler runs it after the Open Library part).
        bookRepository.findSeriesBooksMissingWikidataInfo(LibraryType.BOOK).stream()
                .map(BookEntity::getId)
                .filter(dispatched::add)
                .forEach(this::sendBookFound);

        // Series-less books whose author has a series retry Wikidata series discovery: theirs may
        // have run before the series existed, or failed against Wikidata at the time. Without this
        // an already-enriched book (Open Library row + cover) is never dispatched again and stays
        // out of its series forever.
        bookRepository.findSerieslessBooksOfAuthorsWithSeries(LibraryType.BOOK).stream()
                .map(BookEntity::getId)
                .filter(dispatched::add)
                .forEach(this::sendBookFound);

        List<BookEntity> books = bookRepository.findByLibraryEntity_LibraryType(LibraryType.BOOK);
        books.stream()
                .map(BookEntity::getPersonEntity)
                .collect(Collectors.toMap(PersonEntity::getId, p -> p, (a, b) -> a))
                .values()
                .forEach(bookSeriesService::applyPrefixHeuristic);
        bookSeriesService.cleanupOrphanSeries();

        // Books still missing a series or position re-parse their album.nfo: the nfo <set> and
        // the "{series} {N} - " review opening are the only local source for audiobook-only books
        // (no epub), and a plain rescan never re-fires NFO_FILE_FOUND for a known nfo file. Runs
        // after the prefix heuristic so a heuristic-assigned series gets its index filled in the
        // same pass. Fill-only in the handler, so re-dispatching is idempotent and stops matching
        // once series and index are set.
        books.stream()
                .filter(book -> book.getSeriesEntity() == null || book.getSeriesIndex() == null)
                .forEach(this::sendNfoFileFoundForBook);
    }

    private void sendNfoFileFoundForBook(BookEntity book) {
        metadataRepository.findByBookEntityId(book.getId()).stream()
                .filter(m -> m.getSourceUri() != null
                        && m.getSourceUri().startsWith("file://")
                        && m.getSourceUri().endsWith(".nfo"))
                .map(otherPathFileRepository::findByMetadataEntity)
                .flatMap(Optional::stream)
                .forEach(nfoFile -> directoryRepository.findById(nfoFile.getDirectoryEntityId())
                        .ifPresent(dir -> messageSender.sendNfoFileFound(
                                NfoFileFoundData.builder()
                                        .eventType(EventType.NFO_FILE_FOUND)
                                        .directoryEntityUUID(dir.getId())
                                        .path(nfoFile.getPath())
                                        .build(),
                                dir.getName())));
    }

    private void sendBookFound(UUID bookId) {
        messageSender.sendBookFound(
                BookFoundData.builder().eventType(EventType.BOOK_FOUND).bookId(bookId).build());
    }

    /**
     * Music artists and book authors are both persons; PERSON_FOUND picks the metadata source.
     * Deliberately unrouted: the enrichment handler (MusicBrainz/Open Library/Wikipedia) is the
     * worker's, which listens on the global queue. A node-scoped send would land on the disk
     * handler's queue instead, which only re-parses artist.nfo — no external lookup would happen.
     */
    private void dispatchMissingPersonMetadataEvents() {
        Stream.of(LibraryType.MUSIC, LibraryType.BOOK)
                .flatMap(type -> personRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(type).stream())
                .forEach(p -> messageSender.sendPersonFound(
                        PersonFoundData.builder().eventType(EventType.PERSON_FOUND).personId(p.getId()).build()));
    }

    private void dispatchMissingMusicMetadataEvents(String nodeName) {
        albumRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.MUSIC)
                .forEach(a -> messageSender.sendAlbumFound(
                        AlbumFoundData.builder().eventType(EventType.ALBUM_FOUND).albumId(a.getId()).build(),
                        nodeName));

        albumRepository.findByLibraryEntity_LibraryTypeAndImageEntitiesIsEmpty(LibraryType.MUSIC)
                .forEach(a -> messageSender.sendAlbumFound(
                        AlbumFoundData.builder().eventType(EventType.ALBUM_FOUND).albumId(a.getId()).build()));

        trackRepository.findByAlbumEntity_LibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(LibraryType.MUSIC)
                .forEach(t -> mediaFileRepository.findByTrackEntityId(t.getId()).stream()
                        .filter(m -> m.getDirectoryEntityId() != null)
                        .forEach(m -> directoryRepository.findById(m.getDirectoryEntityId())
                                .ifPresent(dir -> messageSender.sendAudioFileFound(
                                        AudioFileFoundData.fromMediaFileEntity(m), dir.getName()))));
    }

    private void dispatchMissingMetadataEvents(String nodeName) {
        showRepository.findIdsOfShowsWithoutMetadataForNode(nodeName)
                .forEach(id -> messageSender.sendShowFound(
                        ShowFoundData.builder()
                                .eventType(EventType.SHOW_FOUND)
                                .showId(id)
                                .build()));

        episodeRepository.findIdsOfEpisodesWithoutMetadataForNode(nodeName)
                .forEach(id -> messageSender.sendEpisodeFound(
                        EpisodeFoundData.builder()
                                .eventType(EventType.EPISODE_FOUND)
                                .episodeId(id)
                                .build()));

        movieRepository.findIdsOfMoviesWithoutMetadataForNode(nodeName)
                .forEach(id -> messageSender.sendMovieFound(
                        MovieFoundData.builder()
                                .eventType(EventType.MOVIE_FOUND)
                                .movieId(id)
                                .build()));
    }
}
