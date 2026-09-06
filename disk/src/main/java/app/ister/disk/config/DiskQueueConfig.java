package app.ister.disk.config;

import app.ister.core.config.DirectoryQueueNames;
import app.ister.core.config.HelperJob;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static app.ister.core.MessageQueue.*;

@Configuration
public class DiskQueueConfig {

    /** Owner-only events: consumed for this node's own directories plus its cache directory. */
    static final List<String> OWNER_BASES = List.of(
            APP_ISTER_SERVER_FILE_SCAN_REQUESTED,
            APP_ISTER_SERVER_MEDIA_FILE_FOUND,
            APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED,
            APP_ISTER_SERVER_NFO_FILE_FOUND,
            APP_ISTER_SERVER_SUBTITLE_FILE_FOUND,
            APP_ISTER_SERVER_IMAGE_FOUND,
            APP_ISTER_SERVER_UPDATE_IMAGES_REQUESTED,
            APP_ISTER_SERVER_ANALYZE_DATA,
            APP_ISTER_SERVER_PRE_TRANSCODE_RECENTLY_WATCHED,
            APP_ISTER_SERVER_AUDIO_FILE_FOUND,
            APP_ISTER_SERVER_EPUB_FILE_FOUND,
            APP_ISTER_SERVER_COMIC_FILE_FOUND
    );

    @Bean
    public Declarables diskQueueDeclarables(DirectoryQueueNames names) {
        Stream<String> ownerQueues = OWNER_BASES.stream().flatMap(base -> Arrays.stream(names.queues(base)));
        // Helper-capable families: declared for the own directories even when offloaded (the
        // owner keeps publishing to them) and for every helper disk this node serves.
        Stream<String> helperQueues = Stream.of(
                        names.queues(APP_ISTER_SERVER_DETECT_SEGMENTS),
                        names.queues(APP_ISTER_SERVER_DETECT_SEGMENTS, HelperJob.DETECT_SEGMENTS),
                        names.queues(APP_ISTER_SERVER_SUBTITLE_EXTRACT_REQUESTED),
                        names.queues(APP_ISTER_SERVER_SUBTITLE_EXTRACT_REQUESTED, HelperJob.SUBTITLES))
                .flatMap(Arrays::stream);
        Stream<String> nodeQueues = Stream.of(
                APP_ISTER_SERVER_PERSON_FOUND + "." + names.nodeName(),
                APP_ISTER_SERVER_ALBUM_FOUND + "." + names.nodeName(),
                // Podcast downloads are cache-directory-scoped: the audio lands on this node.
                APP_ISTER_SERVER_PODCAST_EPISODE_DOWNLOAD_REQUESTED + "." + names.cacheDirName()
        );
        return new Declarables(
                Stream.of(ownerQueues, helperQueues, nodeQueues)
                        .flatMap(s -> s)
                        .distinct()
                        .map(Queue::new)
                        .toList()
        );
    }

}
