package app.ister.worker.events.analyzelibraryrequested;

import app.ister.core.Handle;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.AlbumFoundData;
import app.ister.core.eventdata.AnalyzeLibraryRequestedData;
import app.ister.core.eventdata.PersonFoundData;
import app.ister.core.eventdata.AudioFileFoundData;
import app.ister.core.eventdata.EpisodeFoundData;
import app.ister.core.eventdata.MovieFoundData;
import app.ister.core.eventdata.ShowFoundData;
import app.ister.core.eventdata.UpdateImagesRequestedData;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.NodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Stream;

/**
 * Handles {@link EventType#ANALYZE_LIBRARY_REQUEST} by:
 * 1. Publishing an update-images event for this node.
 * 2. Publishing "metadata-missing" events for shows, episodes and movies.
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
    private final MediaFileRepository mediaFileRepository;
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
        dispatchMissingPersonMetadataEvents(nodeEntity.getName());
        dispatchMissingMusicMetadataEvents(nodeEntity.getName());
    }

    /** Music artists and book authors are both persons; PERSON_FOUND picks the metadata source. */
    private void dispatchMissingPersonMetadataEvents(String nodeName) {
        Stream.of(LibraryType.MUSIC, LibraryType.BOOK)
                .flatMap(type -> personRepository.findByLibraryEntity_LibraryTypeAndMetadataEntitiesIsEmpty(type).stream())
                .forEach(p -> messageSender.sendPersonFound(
                        PersonFoundData.builder().eventType(EventType.PERSON_FOUND).personId(p.getId()).build(),
                        nodeName));
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
