package app.ister.worker.events.analyzedata;

import app.ister.core.Handle;
import app.ister.core.MessageQueue;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.AlbumFoundData;
import app.ister.core.eventdata.AnalyzeData;
import app.ister.core.eventdata.PersonFoundData;
import app.ister.core.eventdata.AudioFileFoundData;
import app.ister.core.eventdata.EpisodeFoundData;
import app.ister.core.eventdata.MovieFoundData;
import app.ister.core.eventdata.ShowFoundData;
import app.ister.core.repository.*;
import app.ister.core.service.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
    private final DirectoryRepository directoryRepository;
    private final MessageSender messageSender;
    private final MetadataRepository metadataRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final ImageRepository imageRepository;

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
        } else if (data.getLibraryId() != null) {
            handleLibrary(data);
        } else if (data.getShowId() != null) {
            handleShow(data);
        } else if (data.getEpisodeId() != null) {
            episodeRepository.findById(data.getEpisodeId()).ifPresent(episodeEntity -> {
                metadataRepository.deleteAll(episodeEntity.getMetadataEntities());
                imageRepository.deleteAll(episodeEntity.getImagesEntities());
                episodeEntity.getMediaFileEntities().forEach(mediaFileEntity -> mediaFileStreamRepository.deleteAll(mediaFileEntity.getMediaFileStreamEntity()));
                messageSender.sendEpisodeFound(
                        EpisodeFoundData.builder().eventType(EventType.EPISODE_FOUND).episodeId(data.getEpisodeId()).build());
                startAnalyzeMediaFiles(episodeEntity.getMediaFileEntities(), data);
            });
        } else if (data.getMovieId() != null) {
            movieRepository.findById(data.getMovieId()).ifPresent(movieEntity -> {
                metadataRepository.deleteAll(movieEntity.getMetadataEntities());
                imageRepository.deleteAll(movieEntity.getImagesEntities());
                movieEntity.getMediaFileEntities().forEach(mediaFileEntity -> mediaFileStreamRepository.deleteAll(mediaFileEntity.getMediaFileStreamEntity()));
                messageSender.sendMovieFound(
                        MovieFoundData.builder().eventType(EventType.MOVIE_FOUND).movieId(data.getMovieId()).build());
                startAnalyzeMediaFiles(movieEntity.getMediaFileEntities(), data);
            });
        }
    }

    private void handlePerson(AnalyzeData data) {
        personRepository.findById(data.getPersonId()).ifPresent(artist -> {
            metadataRepository.deleteAll(artist.getMetadataEntities());
            imageRepository.deleteAll(artist.getImageEntities());
            directoryRepository.findByLibraryEntityAndDirectoryType(artist.getLibraryEntity(), DirectoryType.LIBRARY)
                    .stream()
                    .map(dir -> dir.getNodeEntity().getName())
                    .distinct()
                    .forEach(nodeName -> messageSender.sendPersonFound(
                            PersonFoundData.builder().eventType(EventType.PERSON_FOUND).personId(data.getPersonId()).build(),
                            nodeName));
            artist.getAlbumEntities().forEach(album -> messageSender.sendAnalyzeData(
                    AnalyzeData.builder().eventType(EventType.ANALYZE_DATA).albumId(album.getId()).build()));
        });
    }

    private void handleAlbum(AnalyzeData data) {
        albumRepository.findById(data.getAlbumId()).ifPresent(album -> {
            metadataRepository.deleteAll(album.getMetadataEntities());
            imageRepository.deleteAll(album.getImageEntities());
            directoryRepository.findByLibraryEntityAndDirectoryType(album.getLibraryEntity(), DirectoryType.LIBRARY)
                    .stream()
                    .map(dir -> dir.getNodeEntity().getName())
                    .distinct()
                    .forEach(nodeName -> messageSender.sendAlbumFound(
                            AlbumFoundData.builder().eventType(EventType.ALBUM_FOUND).albumId(data.getAlbumId()).build(),
                            nodeName));
            messageSender.sendAlbumFound(
                    AlbumFoundData.builder().eventType(EventType.ALBUM_FOUND).albumId(data.getAlbumId()).build());
            album.getTrackEntities().forEach(track -> messageSender.sendAnalyzeData(
                    AnalyzeData.builder().eventType(EventType.ANALYZE_DATA).trackId(track.getId()).build()));
        });
    }

    private void handleTrack(AnalyzeData data) {
        trackRepository.findById(data.getTrackId()).ifPresent(track -> {
            metadataRepository.deleteAll(track.getMetadataEntities());
            track.getMediaFileEntities().stream()
                    .filter(m -> m.getDirectoryEntity() != null)
                    .forEach(m -> messageSender.sendAudioFileFound(
                            AudioFileFoundData.fromMediaFileEntity(m), m.getDirectoryEntity().getName()));
        });
    }

    private void handleLibrary(AnalyzeData data) {
        LibraryEntity library = libraryRepository.findById(data.getLibraryId()).orElseThrow();
        if (library.getLibraryType() == LibraryType.SHOW) {
            showRepository.findIdsByLibraryId(data.getLibraryId())
                    .forEach(showId -> messageSender.sendAnalyzeData(
                            AnalyzeData.builder()
                                    .eventType(EventType.ANALYZE_DATA)
                                    .showId(showId)
                                    .build()));
        } else if (library.getLibraryType() == LibraryType.MUSIC) {
            personRepository.findByLibraryEntityId(data.getLibraryId())
                    .forEach(artist -> messageSender.sendAnalyzeData(
                            AnalyzeData.builder()
                                    .eventType(EventType.ANALYZE_DATA)
                                    .personId(artist.getId())
                                    .build()));
        } else {
            movieRepository.findIdsByLibraryId(data.getLibraryId())
                    .forEach(movieId -> messageSender.sendAnalyzeData(
                            AnalyzeData.builder()
                                    .eventType(EventType.ANALYZE_DATA)
                                    .movieId(movieId)
                                    .build()));
        }
    }

    private void handleShow(AnalyzeData data) {
        showRepository.findById(data.getShowId()).ifPresent(showEntity -> {
            metadataRepository.deleteAll(showEntity.getMetadataEntities());
            imageRepository.deleteAll(showEntity.getImageEntities());
            messageSender.sendShowFound(
                    ShowFoundData.builder()
                            .eventType(EventType.SHOW_FOUND)
                            .showId(data.getShowId())
                            .build());
            episodeRepository.findByShowEntityId(data.getShowId(), Sort.by("number"))
                    .forEach(episode -> messageSender.sendAnalyzeData(
                            AnalyzeData.builder()
                                    .eventType(EventType.ANALYZE_DATA)
                                    .episodeId(episode.getId())
                                    .build()));
        });
    }

    private void startAnalyzeMediaFiles(List<MediaFileEntity> mediaFileEntityList, AnalyzeData data) {
        // One event per unique directory (images/NFO/subtitles are directory-level resources)
        mediaFileEntityList.stream()
                .map(MediaFileEntity::getDirectoryEntity)
                .distinct()
                .forEach(dir -> messageSender.sendAnalyzeData(
                        AnalyzeData.builder()
                                .eventType(EventType.ANALYZE_DATA)
                                .episodeId(data.getEpisodeId())
                                .movieId(data.getMovieId())
                                .directoryId(dir.getId())
                                .build(),
                        dir.getName()));

    }
}
