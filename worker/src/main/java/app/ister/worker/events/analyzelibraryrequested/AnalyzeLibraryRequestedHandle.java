package app.ister.worker.events.analyzelibraryrequested;

import app.ister.core.MessageQueue;
import app.ister.core.entitiy.ImageEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.AnalyzeLibraryRequestedData;
import app.ister.core.eventdata.EpisodeFoundData;
import app.ister.core.eventdata.MovieFoundData;
import app.ister.core.eventdata.ShowFoundData;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.service.MessageSender;
import app.ister.worker.events.Handle;
import io.trbl.blurhash.BlurHash;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles {@link EventType#ANALYZE_LIBRARY_REQUEST} by:
 * 1. Updating blur‑hashes for images whose file timestamps changed.
 * 2. Publishing “metadata‑missing” events for shows, episodes and movies.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class AnalyzeLibraryRequestedHandle implements Handle<AnalyzeLibraryRequestedData> {

    private final ImageRepository imageRepository;
    private final ShowRepository showRepository;
    private final EpisodeRepository episodeRepository;
    private final MovieRepository movieRepository;
    private final MessageSender messageSender;

    /**
     * RabbitMQ entry point
     */
    @RabbitListener(queues = MessageQueue.APP_ISTER_SERVER_ANALYZE_LIBRARY_REQUESTED)
    @Override
    public void listener(AnalyzeLibraryRequestedData data) {
        Handle.super.listener(data);
    }

    @Override
    public EventType handles() {
        return EventType.ANALYZE_LIBRARY_REQUEST;
    }

    @Override
    public Boolean handle(AnalyzeLibraryRequestedData data) {
        updateImages();
        dispatchMissingMetadataEvents();
        return true;
    }

    /**
     * Image processing
     */
    private void updateImages() {
        List<ImageEntity> toUpdate = new ArrayList<>();
        imageRepository.findAll().forEach(imageEntity -> {
            if (needsRefresh(imageEntity)) {
                applyBlurHash(imageEntity);
                toUpdate.add(imageEntity);
            }
        });
        if (!toUpdate.isEmpty()) {
            imageRepository.saveAll(toUpdate);
        }
    }

    /**
     * Returns true when the file on disk is newer than the stored timestamps.
     */
    private boolean needsRefresh(ImageEntity imageEntity) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(
                    Path.of(imageEntity.getPath()), BasicFileAttributes.class);

            Instant fileMod = attrs.lastModifiedTime().toInstant();
            Instant storedMod = imageEntity.getFileLastModifiedTime();

            return !fileMod.equals(storedMod);
        } catch (IOException e) {
            log.error("Failed to read attributes for {}: {}", imageEntity.getPath(), e.getMessage());
            return false;
        }
    }

    /**
     * Calculates a blur‑hash and updates the entity’s timestamps.
     */
    private void applyBlurHash(ImageEntity imageEntity) {
        try {
            BufferedImage bi = ImageIO.read(new File(imageEntity.getPath()));
            String blurHash = BlurHash.encode(bi);

            BasicFileAttributes attrs = Files.readAttributes(
                    Path.of(imageEntity.getPath()), BasicFileAttributes.class);

            imageEntity.setBlurHash(blurHash);
            imageEntity.setFileLastModifiedTime(attrs.lastModifiedTime().toInstant());
            imageEntity.setFileCreationTime(attrs.creationTime().toInstant());

            log.debug("Updated blur‑hash for {}", imageEntity.getPath());
        } catch (IOException e) {
            log.error("Unable to process imageEntity {}: {}", imageEntity.getPath(), e.getMessage());
        }
    }

    /**
     * Dispatch “metadata missing” events.
     */
    private void dispatchMissingMetadataEvents() {
        showRepository.findIdsOfShowsWithoutMetadata()
                .forEach(id -> messageSender.sendShowFound(
                        ShowFoundData.builder()
                                .eventType(EventType.SHOW_FOUND)
                                .showId(id)
                                .build()));

        episodeRepository.findIdsOfEpisodesWithoutMetadata()
                .forEach(id -> messageSender.sendEpisodeFound(
                        EpisodeFoundData.builder()
                                .eventType(EventType.EPISODE_FOUND)
                                .episodeId(id)
                                .build()));

        movieRepository.findIdsOfMoviesWithoutMetadata()
                .forEach(id -> messageSender.sendMovieFound(
                        MovieFoundData.builder()
                                .eventType(EventType.MOVIE_FOUND)
                                .movieId(id)
                                .build()));
    }
}
