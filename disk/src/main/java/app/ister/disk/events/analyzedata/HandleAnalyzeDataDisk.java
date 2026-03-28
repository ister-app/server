package app.ister.disk.events.analyzedata;

import app.ister.core.Handle;
import app.ister.core.entity.*;
import app.ister.core.enums.EventType;
import app.ister.core.enums.StreamCodecType;
import app.ister.core.eventdata.AnalyzeData;
import app.ister.core.eventdata.MediaFileFoundData;
import app.ister.core.eventdata.NfoFileFoundData;
import app.ister.core.eventdata.SubtitleFileFoundData;
import app.ister.core.repository.*;
import app.ister.core.service.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class HandleAnalyzeDataDisk implements Handle<AnalyzeData> {

    private final DirectoryRepository directoryRepository;
    private final EpisodeRepository episodeRepository;
    private final MovieRepository movieRepository;
    private final MediaFileStreamRepository mediaFileStreamRepository;
    private final OtherPathFileRepository otherPathFileRepository;
    private final MessageSender messageSender;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getAnalyzeDataQueues()}")
    @Override
    public void listener(AnalyzeData data) {
        Handle.super.listener(data);
    }

    @Override
    public EventType handles() {
        return EventType.ANALYZE_DATA;
    }

    @Override
    public Boolean handle(AnalyzeData data) {
        DirectoryEntity dir = directoryRepository.findById(data.getDirectoryId()).orElseThrow();

        List<MetadataEntity> metadataEntities;
        List<MediaFileEntity> localFiles;
        if (data.getEpisodeId() != null) {
            EpisodeEntity episode = episodeRepository.findById(data.getEpisodeId()).orElseThrow();
            localFiles = episode.getMediaFileEntities().stream()
                    .filter(mf -> mf.getDirectoryEntityId().equals(dir.getId())).toList();
            metadataEntities = episode.getMetadataEntities();
        } else {
            MovieEntity movie = movieRepository.findById(data.getMovieId()).orElseThrow();
            localFiles = movie.getMediaFileEntities().stream()
                    .filter(mf -> mf.getDirectoryEntityId().equals(dir.getId())).toList();
            metadataEntities = movie.getMetadataEntities();
        }

        for (MediaFileEntity mf : localFiles) {
            deleteHlsCache(mf.getId());
            mediaFileStreamRepository.deleteAllByMediaFileEntityId(mf.getId());
            messageSender.sendMediaFileFound(
                    MediaFileFoundData.builder()
                            .eventType(EventType.MEDIA_FILE_FOUND)
                            .directoryEntityUUID(dir.getId())
                            .path(mf.getPath())
                            .episodeEntityUUID(data.getEpisodeId())
                            .movieEntityUUID(data.getMovieId())
                            .build(),
                    dir.getName());
        }

        metadataEntities.forEach(m ->
                otherPathFileRepository.findByMetadataEntity(m).ifPresent(f ->
                        messageSender.sendNfoFileFound(
                                NfoFileFoundData.builder().eventType(EventType.NFO_FILE_FOUND)
                                        .directoryEntityUUID(dir.getId()).path(f.getPath()).build(),
                                dir.getName())));

        localFiles.forEach(mf ->
                mediaFileStreamRepository.findByMediaFileEntity_IdAndCodecType(mf.getId(), StreamCodecType.EXTERNAL_SUBTITLE)
                        .forEach(stream ->
                                otherPathFileRepository.findByMediaFileStreamEntity(stream).ifPresent(f ->
                                        messageSender.sendSubtitleFileFound(
                                                SubtitleFileFoundData.builder().eventType(EventType.SUBTITLE_FILE_FOUND)
                                                        .directoryEntityUUID(dir.getId()).path(f.getPath()).build(),
                                                dir.getName()))));

        return true;
    }

    private void deleteHlsCache(UUID mediaFileId) {
        Path dir = Paths.get(tmpDir, mediaFileId.toString());
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("Could not delete HLS cache file {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Could not delete HLS cache for {}: {}", mediaFileId, e.getMessage());
        }
    }
}
