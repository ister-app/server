package app.ister.worker.events.analyzedata;

import app.ister.core.Handle;
import app.ister.core.MessageQueue;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.AnalyzeData;
import app.ister.core.repository.*;
import app.ister.core.service.MessageSender;
import app.ister.worker.events.common.FoundEventDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static app.ister.core.utils.AfterCommitPublisher.publishAfterCommit;

/**
 * The FORCE refresh flow: deletes stored metadata/artwork/stream info first, then re-emits the
 * {@code *_FOUND} events so everything is re-fetched from the metadata services. Contrast with the
 * metadata backfill ({@code METADATA_BACKFILL_REQUESTED}), which only dispatches missing items.
 *
 * <p>Deleting an ImageEntity row leaves its cache file behind on the node that downloaded it —
 * unlinking here would be unsafe (this handler may run on any node); the daily
 * {@code CacheCleanupScheduler} reclaims orphaned files (mind {@code cache-cleanup.dry-run}).
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class AnalyzeDataHandle implements Handle<AnalyzeData> {

    private final EpisodeRepository episodeRepository;
    private final MovieRepository movieRepository;
    private final ShowRepository showRepository;
    private final LibraryRepository libraryRepository;
    private final PersonRepository personRepository;
    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;
    private final BookRepository bookRepository;
    private final SeriesRepository seriesRepository;
    private final DirectoryRepository directoryRepository;
    private final MessageSender messageSender;
    private final MetadataRepository metadataRepository;
    private final MediaFileRepository mediaFileRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final ImageRepository imageRepository;
    private final FoundEventDispatcher dispatcher;

    @RabbitListener(queues = MessageQueue.APP_ISTER_SERVER_ANALYZE_DATA)
    @Override
    public void listener(AnalyzeData data) {
        Handle.super.listener(data);
    }

    @Override
    public EventType handles() {
        return EventType.ANALYZE_DATA;
    }

    @Override
    public void handle(AnalyzeData data) {
        if (data.getPersonId() != null) {
            handlePerson(data);
        } else if (data.getAlbumId() != null) {
            handleAlbum(data);
        } else if (data.getTrackId() != null) {
            handleTrack(data);
        } else if (data.getBookId() != null) {
            handleBook(data);
        } else if (data.getSeriesId() != null) {
            handleSeries(data);
        } else if (data.getLibraryId() != null) {
            handleLibrary(data);
        } else if (data.getShowId() != null) {
            handleShow(data);
        } else if (data.getEpisodeId() != null) {
            episodeRepository.findById(data.getEpisodeId()).ifPresent(episodeEntity -> {
                metadataRepository.deleteAll(metadataRepository.findByEpisodeEntityId(data.getEpisodeId()));
                imageRepository.deleteAll(imageRepository.findByEpisodeEntityId(data.getEpisodeId()));
                List<MediaFileEntity> mediaFiles = mediaFileRepository.findByEpisodeEntityId(data.getEpisodeId());
                mediaFiles.forEach(mediaFileEntity -> mediaFileStreamRepository.deleteAllByMediaFileEntityId(mediaFileEntity.getId()));
                // The *_FOUND consumer checks for existing metadata/image rows; publish only after
                // the deletes above have committed or it sees the doomed rows and skips the refetch.
                publishAfterCommit(() -> dispatcher.episodeFound(data.getEpisodeId()));
                startAnalyzeMediaFiles(mediaFiles, data);
            });
        } else if (data.getMovieId() != null) {
            movieRepository.findById(data.getMovieId()).ifPresent(movieEntity -> {
                metadataRepository.deleteAll(metadataRepository.findByMovieEntityId(data.getMovieId()));
                imageRepository.deleteAll(imageRepository.findByMovieEntityId(data.getMovieId()));
                List<MediaFileEntity> mediaFiles = mediaFileRepository.findByMovieEntityId(data.getMovieId());
                mediaFiles.forEach(mediaFileEntity -> mediaFileStreamRepository.deleteAllByMediaFileEntityId(mediaFileEntity.getId()));
                publishAfterCommit(() -> dispatcher.movieFound(data.getMovieId()));
                startAnalyzeMediaFiles(mediaFiles, data);
            });
        }
    }

    private void handlePerson(AnalyzeData data) {
        personRepository.findById(data.getPersonId()).ifPresent(person -> {
            metadataRepository.deleteAll(metadataRepository.findByPersonEntityId(person.getId()));
            imageRepository.deleteAll(imageRepository.findByPersonEntityId(person.getId()));
            // Node-scoped for the disk handler (artist.nfo / folder artwork) AND global for the
            // worker's enrichment handler (MusicBrainz/Open Library/Wikipedia) — the node-scoped
            // send alone never reaches the external lookup.
            directoryRepository.findByLibraryEntityAndDirectoryType(person.getLibraryEntity(), DirectoryType.LIBRARY)
                    .stream()
                    .map(dir -> dir.getNodeEntity().getName())
                    .distinct()
                    .forEach(nodeName -> publishAfterCommit(() ->
                            dispatcher.personFoundToNode(data.getPersonId(), nodeName)));
            publishAfterCommit(() -> dispatcher.personFoundGlobal(data.getPersonId()));
            albumRepository.findByPersonEntityId(person.getId()).forEach(album -> publishAfterCommit(() -> messageSender.sendAnalyzeData(
                    AnalyzeData.builder().eventType(EventType.ANALYZE_DATA).albumId(album.getId()).build())));
            bookRepository.findByPersonEntityId(person.getId()).forEach(book -> publishAfterCommit(() -> messageSender.sendAnalyzeData(
                    AnalyzeData.builder().eventType(EventType.ANALYZE_DATA).bookId(book.getId()).build())));
        });
    }

    private void handleAlbum(AnalyzeData data) {
        albumRepository.findById(data.getAlbumId()).ifPresent(album -> {
            metadataRepository.deleteAll(metadataRepository.findByAlbumEntityId(album.getId()));
            imageRepository.deleteAll(imageRepository.findByAlbumEntityId(album.getId()));
            directoryRepository.findByLibraryEntityAndDirectoryType(album.getLibraryEntity(), DirectoryType.LIBRARY)
                    .stream()
                    .map(dir -> dir.getNodeEntity().getName())
                    .distinct()
                    .forEach(nodeName -> publishAfterCommit(() ->
                            dispatcher.albumFoundToNode(data.getAlbumId(), nodeName)));
            publishAfterCommit(() -> dispatcher.albumFoundGlobal(data.getAlbumId()));
            trackRepository.findByAlbumEntity_Id(album.getId(), Sort.unsorted()).forEach(track -> publishAfterCommit(() -> messageSender.sendAnalyzeData(
                    AnalyzeData.builder().eventType(EventType.ANALYZE_DATA).trackId(track.getId()).build())));
        });
    }

    private void handleTrack(AnalyzeData data) {
        trackRepository.findById(data.getTrackId()).ifPresent(track -> {
            metadataRepository.deleteAll(metadataRepository.findByTrackEntityId(track.getId()));
            Map<UUID, DirectoryEntity> directories = directoriesById();
            mediaFileRepository.findByTrackEntityId(track.getId()).stream()
                    .filter(m -> m.getDirectoryEntityId() != null)
                    .forEach(m -> Optional.ofNullable(directories.get(m.getDirectoryEntityId()))
                            .ifPresent(dir -> {
                                var audioFileFoundData = app.ister.core.eventdata.AudioFileFoundData.fromMediaFileEntity(m);
                                publishAfterCommit(() -> messageSender.sendAudioFileFound(audioFileFoundData, dir.getName()));
                            }));
        });
    }

    /**
     * Force refresh of one book: wipe its metadata and cover, then re-enter through the epub parse
     * (EPUB_FILE_FOUND writes release date and ISBN, then chains BOOK_FOUND — dispatching
     * BOOK_FOUND directly would race the Open Library lookup against the ISBN being stored).
     * Audiobook-only books have no epub and go straight to BOOK_FOUND.
     */
    private void handleBook(AnalyzeData data) {
        bookRepository.findById(data.getBookId()).ifPresent(book -> {
            metadataRepository.deleteAll(metadataRepository.findByBookEntityId(book.getId()));
            imageRepository.deleteAll(imageRepository.findByBookEntityId(book.getId()));
            Map<UUID, DirectoryEntity> directories = directoriesById();
            List<MediaFileEntity> epubs = mediaFileRepository.findByBookEntityId(book.getId()).stream()
                    .filter(m -> m.getDirectoryEntityId() != null)
                    .toList();
            if (epubs.isEmpty()) {
                publishAfterCommit(() -> dispatcher.bookFound(book.getId()));
                return;
            }
            epubs.forEach(m -> Optional.ofNullable(directories.get(m.getDirectoryEntityId()))
                    .ifPresent(dir -> publishAfterCommit(() -> dispatcher.epubFileFound(dir, book.getId(), m))));
        });
    }

    /**
     * Force refresh of one comic series: wipe the series' metadata and artwork, retry the
     * Wikipedia lookup, and re-parse every volume's file (cover, page count, embedded
     * ComicInfo.xml).
     */
    private void handleSeries(AnalyzeData data) {
        seriesRepository.findById(data.getSeriesId()).ifPresent(series -> {
            metadataRepository.deleteAll(metadataRepository.findBySeriesEntityId(series.getId()));
            imageRepository.deleteAll(imageRepository.findBySeriesEntityId(series.getId()));
            publishAfterCommit(() -> dispatcher.comicSeriesFound(series.getId()));
            Map<UUID, DirectoryEntity> directories = directoriesById();
            bookRepository.findBySeriesEntityId(series.getId()).forEach(volume -> {
                imageRepository.deleteAll(imageRepository.findByBookEntityId(volume.getId()));
                mediaFileRepository.findByBookEntityId(volume.getId()).stream()
                        .filter(m -> m.getDirectoryEntityId() != null)
                        .forEach(m -> Optional.ofNullable(directories.get(m.getDirectoryEntityId()))
                                .ifPresent(dir -> publishAfterCommit(() ->
                                        dispatcher.comicVolumeFileFound(dir, volume.getId(), m))));
            });
        });
    }

    private void handleLibrary(AnalyzeData data) {
        LibraryEntity library = libraryRepository.findById(data.getLibraryId()).orElseThrow();
        switch (library.getLibraryType()) {
            case SHOW -> showRepository.findIdsByLibraryId(data.getLibraryId())
                    .forEach(showId -> publishAfterCommit(() -> messageSender.sendAnalyzeData(
                            AnalyzeData.builder().eventType(EventType.ANALYZE_DATA).showId(showId).build())));
            case MOVIE -> movieRepository.findIdsByLibraryId(data.getLibraryId())
                    .forEach(movieId -> publishAfterCommit(() -> messageSender.sendAnalyzeData(
                            AnalyzeData.builder().eventType(EventType.ANALYZE_DATA).movieId(movieId).build())));
            case MUSIC, BOOK -> personRepository.findByLibraryEntityId(data.getLibraryId())
                    .forEach(person -> publishAfterCommit(() -> messageSender.sendAnalyzeData(
                            AnalyzeData.builder().eventType(EventType.ANALYZE_DATA).personId(person.getId()).build())));
            case COMIC -> seriesRepository.findAllByLibraryEntityId(data.getLibraryId())
                    .forEach(series -> publishAfterCommit(() -> messageSender.sendAnalyzeData(
                            AnalyzeData.builder().eventType(EventType.ANALYZE_DATA).seriesId(series.getId()).build())));
            default -> log.warn("Force refresh is not supported for library {} of type {}; skipping",
                    library.getName(), library.getLibraryType());
        }
    }

    private void handleShow(AnalyzeData data) {
        showRepository.findById(data.getShowId()).ifPresent(showEntity -> {
            metadataRepository.deleteAll(metadataRepository.findByShowEntityId(data.getShowId()));
            imageRepository.deleteAll(imageRepository.findByShowEntityId(data.getShowId()));
            publishAfterCommit(() -> dispatcher.showFound(data.getShowId()));
            episodeRepository.findByShowEntityId(data.getShowId(), Sort.by("number"))
                    .forEach(episode -> publishAfterCommit(() -> messageSender.sendAnalyzeData(
                            AnalyzeData.builder()
                                    .eventType(EventType.ANALYZE_DATA)
                                    .episodeId(episode.getId())
                                    .build())));
        });
    }

    private void startAnalyzeMediaFiles(List<MediaFileEntity> mediaFileEntityList, AnalyzeData data) {
        Map<UUID, DirectoryEntity> directories = directoriesById();
        // One event per unique directory (images/NFO/subtitles are directory-level resources)
        mediaFileEntityList.stream()
                .map(MediaFileEntity::getDirectoryEntityId)
                .distinct()
                .forEach(dirId -> Optional.ofNullable(directories.get(dirId)).ifPresent(dir -> publishAfterCommit(() -> messageSender.sendAnalyzeData(
                        AnalyzeData.builder()
                                .eventType(EventType.ANALYZE_DATA)
                                .episodeId(data.getEpisodeId())
                                .movieId(data.getMovieId())
                                .directoryId(dir.getId())
                                .build(),
                        dir.getName()))));
    }

    private Map<UUID, DirectoryEntity> directoriesById() {
        return StreamSupport.stream(directoryRepository.findAll().spliterator(), false)
                .collect(Collectors.toMap(DirectoryEntity::getId, Function.identity()));
    }
}
