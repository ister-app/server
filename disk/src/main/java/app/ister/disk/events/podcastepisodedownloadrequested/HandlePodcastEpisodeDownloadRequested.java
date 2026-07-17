package app.ister.disk.events.podcastepisodedownloadrequested;

import app.ister.core.Handle;
import app.ister.core.EventHandlingException;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.AudioFileFoundData;
import app.ister.core.eventdata.PodcastEpisodeDownloadRequestedData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.NodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Downloads a podcast episode's enclosure to this node's cache directory and registers it as a
 * MediaFileEntity. From there the regular AUDIO_FILE_FOUND pipeline takes over (ffprobe streams +
 * duration, HLS playlist pre-generation), so playback is identical to tracks and chapters.
 * Runs on the node whose cache-directory queue received the event.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class HandlePodcastEpisodeDownloadRequested implements Handle<PodcastEpisodeDownloadRequestedData> {

    private final PodcastEpisodeRepository podcastEpisodeRepository;
    private final MediaFileRepository mediaFileRepository;
    private final DirectoryRepository directoryRepository;
    private final NodeService nodeService;
    private final MessageSender messageSender;

    /**
     * Redirects are followed manually in {@link #download}: podcast enclosures sit behind chains of
     * tracking redirects that regularly exceed the JDK client's 5-hop limit (after which it just
     * returns the last 302), and may hop from https to http, which {@code Redirect.NORMAL} refuses.
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final int MAX_REDIRECTS = 10;

    @Override
    public EventType handles() {
        return EventType.PODCAST_EPISODE_DOWNLOAD_REQUESTED;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getPodcastEpisodeDownloadRequestedQueue()}")
    @Override
    public void listener(PodcastEpisodeDownloadRequestedData data) {
        Handle.super.listener(data);
    }

    @Override
    public void handle(PodcastEpisodeDownloadRequestedData data) {
        Optional<PodcastEpisodeEntity> episodeOptional = podcastEpisodeRepository.findById(data.getPodcastEpisodeId());
        if (episodeOptional.isEmpty()) {
            log.warn("Podcast episode {} not found — skipping download", data.getPodcastEpisodeId());
            return;
        }
        PodcastEpisodeEntity episode = episodeOptional.get();
        if (mediaFileRepository.existsByPodcastEpisodeEntityId(episode.getId())) {
            return; // already downloaded
        }
        DirectoryEntity cacheDir = directoryRepository
                .findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeService.getOrCreateNodeEntityForThisNode())
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No cache directory found for this node"));

        Path target = Path.of(cacheDir.getPath(), "podcasts", episode.getId() + "." + extensionFor(episode));
        download(episode, target);

        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            throw new EventHandlingException("Downloaded episode file unreadable: " + target, e);
        }
        MediaFileEntity mediaFile = mediaFileRepository
                .findByDirectoryEntityAndPath(cacheDir, target.toString())
                .orElseGet(() -> MediaFileEntity.builder()
                        .directoryEntityId(cacheDir.getId())
                        .podcastEpisodeEntity(episode)
                        .path(target.toString())
                        .size(size).build());
        mediaFile.setPodcastEpisodeEntity(episode);
        mediaFileRepository.save(mediaFile);

        sendAudioFileFoundAfterCommit(AudioFileFoundData.builder()
                .eventType(EventType.AUDIO_FILE_FOUND)
                .directoryEntityUUID(cacheDir.getId())
                .path(target.toString())
                .build(), cacheDir.getName());
    }

    private void download(PodcastEpisodeEntity episode, Path target) {
        try {
            Files.createDirectories(target.getParent());
            URI uri = URI.create(episode.getEnclosureUrl());
            HttpResponse<InputStream> response = get(uri);
            int hops = 0;
            while (isRedirect(response.statusCode()) && hops < MAX_REDIRECTS) {
                Optional<String> location = response.headers().firstValue("Location");
                try (InputStream body = response.body()) {
                    body.transferTo(OutputStreamDiscard.INSTANCE);
                }
                if (location.isEmpty()) {
                    break; // a redirect without Location falls through to the status check below
                }
                uri = uri.resolve(location.get());
                response = get(uri);
                hops++;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() != 200 || contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
                try (InputStream body = response.body()) {
                    body.transferTo(OutputStreamDiscard.INSTANCE);
                }
                throw new EventHandlingException("Enclosure download failed for " + episode.getEnclosureUrl()
                        + ": status=" + response.statusCode() + " contentType=" + contentType, null);
            }
            try (InputStream body = response.body()) {
                Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Downloaded podcast episode {} to {}", episode.getId(), target);
        } catch (IOException e) {
            throw new EventHandlingException("Could not download enclosure " + episode.getEnclosureUrl(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventHandlingException("Download interrupted for " + episode.getEnclosureUrl(), e);
        }
    }

    private HttpResponse<InputStream> get(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", "IsterServer/1.0 (info@ister.app)")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** File extension from the enclosure URL path, falling back to the MIME type, then mp3. */
    private static String extensionFor(PodcastEpisodeEntity episode) {
        String path = URI.create(episode.getEnclosureUrl()).getPath();
        if (path != null) {
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && path.length() - dot <= 5) {
                return path.substring(dot + 1).toLowerCase(Locale.ROOT);
            }
        }
        String type = episode.getEnclosureType() == null ? "" : episode.getEnclosureType().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "audio/mp4", "audio/x-m4a" -> "m4a";
            case "audio/ogg", "audio/opus" -> "ogg";
            case "audio/aac" -> "aac";
            default -> "mp3";
        };
    }

    private void sendAudioFileFoundAfterCommit(AudioFileFoundData data, String directoryName) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messageSender.sendAudioFileFound(data, directoryName);
            }
        });
    }

    /** Drains an error body without keeping it. */
    private static final class OutputStreamDiscard extends java.io.OutputStream {
        static final OutputStreamDiscard INSTANCE = new OutputStreamDiscard();

        @Override
        public void write(int b) {
            // discard
        }

        @Override
        public void write(byte[] b, int off, int len) {
            // discard
        }
    }
}
