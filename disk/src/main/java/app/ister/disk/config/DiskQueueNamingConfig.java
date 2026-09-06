package app.ister.disk.config;

import app.ister.core.config.DirectoryQueueNames;
import app.ister.core.config.HelperJob;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

import static app.ister.core.MessageQueue.*;

/**
 * Queue names the disk-module listeners subscribe to, referenced from {@code @RabbitListener}
 * SpEL expressions ({@code #{@diskQueueNamingConfig.getXxxQueues()}}). Plain events are
 * consumed for the node's own directories only; the heavy job families go through
 * {@link DirectoryQueueNames#queues(String, HelperJob)} so helper nodes can join in and
 * owners can opt out.
 */
@Configuration
@RequiredArgsConstructor
public class DiskQueueNamingConfig {

    private final DirectoryQueueNames names;

    public String[] getFileScanRequestedQueues() {
        return names.queues(APP_ISTER_SERVER_FILE_SCAN_REQUESTED);
    }

    public String[] getMediaFileFoundQueues() {
        return names.queues(APP_ISTER_SERVER_MEDIA_FILE_FOUND);
    }

    public String[] getNewDirectoriesScanRequestedQueues() {
        return names.queues(APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED);
    }

    public String[] getNfoFileFoundQueues() {
        return names.queues(APP_ISTER_SERVER_NFO_FILE_FOUND);
    }

    public String[] getSubtitleFileFoundQueues() {
        return names.queues(APP_ISTER_SERVER_SUBTITLE_FILE_FOUND);
    }

    public String[] getImageFoundQueues() {
        return names.queues(APP_ISTER_SERVER_IMAGE_FOUND);
    }

    public String[] getUpdateImagesRequestedQueues() {
        return names.queues(APP_ISTER_SERVER_UPDATE_IMAGES_REQUESTED);
    }

    public String[] getDetectSegmentsQueues() {
        return names.queues(APP_ISTER_SERVER_DETECT_SEGMENTS, HelperJob.DETECT_SEGMENTS);
    }

    public String[] getSubtitleExtractRequestedQueues() {
        return names.queues(APP_ISTER_SERVER_SUBTITLE_EXTRACT_REQUESTED, HelperJob.SUBTITLES);
    }

    public String[] getAnalyzeDataQueues() {
        return names.queues(APP_ISTER_SERVER_ANALYZE_DATA);
    }

    public String[] getPreTranscodeRecentlyWatchedQueues() {
        return names.queues(APP_ISTER_SERVER_PRE_TRANSCODE_RECENTLY_WATCHED);
    }

    public String[] getAudioFileFoundQueues() {
        return names.queues(APP_ISTER_SERVER_AUDIO_FILE_FOUND);
    }

    public String[] getEpubFileFoundQueues() {
        return names.queues(APP_ISTER_SERVER_EPUB_FILE_FOUND);
    }

    public String[] getComicFileFoundQueues() {
        return names.queues(APP_ISTER_SERVER_COMIC_FILE_FOUND);
    }

    public String getPodcastEpisodeDownloadRequestedQueue() {
        return APP_ISTER_SERVER_PODCAST_EPISODE_DOWNLOAD_REQUESTED + "." + names.cacheDirName();
    }

    public String getPersonFoundQueue() {
        return APP_ISTER_SERVER_PERSON_FOUND + "." + names.nodeName();
    }

    public String getAlbumFoundQueue() {
        return APP_ISTER_SERVER_ALBUM_FOUND + "." + names.nodeName();
    }
}
