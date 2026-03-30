package app.ister.disk.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.stream.Stream;

import static app.ister.core.MessageQueue.*;

@Configuration
@RequiredArgsConstructor
public class DiskQueueNamingConfig {

    private final AppIsterServerConfig config;

    @Value("${app.ister.server.name}")
    private String nodeName;

    private String cacheDirName() {
        return nodeName + "-cache-directory";
    }

    public String[] getFileScanRequestedQueues() {
        return Stream.concat(
                config.getDirectories().stream()
                        .map(dir -> APP_ISTER_SERVER_FILE_SCAN_REQUESTED + "." + dir.getName()),
                Stream.of(APP_ISTER_SERVER_FILE_SCAN_REQUESTED + "." + cacheDirName())
        ).toArray(String[]::new);
    }

    public String[] getMediaFileFoundQueues() {
        return Stream.concat(
                config.getDirectories().stream()
                        .map(dir -> APP_ISTER_SERVER_MEDIA_FILE_FOUND + "." + dir.getName()),
                Stream.of(APP_ISTER_SERVER_MEDIA_FILE_FOUND + "." + cacheDirName())
        ).toArray(String[]::new);
    }

    public String[] getNewDirectoriesScanRequestedQueues() {
        return Stream.concat(
                config.getDirectories().stream()
                        .map(dir -> APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED + "." + dir.getName()),
                Stream.of(APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED + "." + cacheDirName())
        ).toArray(String[]::new);
    }

    public String[] getNfoFileFoundQueues() {
        return Stream.concat(
                config.getDirectories().stream()
                        .map(dir -> APP_ISTER_SERVER_NFO_FILE_FOUND + "." + dir.getName()),
                Stream.of(APP_ISTER_SERVER_NFO_FILE_FOUND + "." + cacheDirName())
        ).toArray(String[]::new);
    }

    public String[] getSubtitleFileFoundQueues() {
        return Stream.concat(
                config.getDirectories().stream()
                        .map(dir -> APP_ISTER_SERVER_SUBTITLE_FILE_FOUND + "." + dir.getName()),
                Stream.of(APP_ISTER_SERVER_SUBTITLE_FILE_FOUND + "." + cacheDirName())
        ).toArray(String[]::new);
    }

    public String[] getImageFoundQueues() {
        return Stream.concat(
                config.getDirectories().stream()
                        .map(dir -> APP_ISTER_SERVER_IMAGE_FOUND + "." + dir.getName()),
                Stream.of(APP_ISTER_SERVER_IMAGE_FOUND + "." + cacheDirName())
        ).toArray(String[]::new);
    }

    public String[] getUpdateImagesRequestedQueues() {
        return Stream.concat(
                config.getDirectories().stream()
                        .map(dir -> APP_ISTER_SERVER_UPDATE_IMAGES_REQUESTED + "." + dir.getName()),
                Stream.of(APP_ISTER_SERVER_UPDATE_IMAGES_REQUESTED + "." + cacheDirName())
        ).toArray(String[]::new);
    }

    public String[] getAnalyzeDataQueues() {
        return Stream.concat(
                config.getDirectories().stream()
                        .map(dir -> APP_ISTER_SERVER_ANALYZE_DATA + "." + dir.getName()),
                Stream.of(APP_ISTER_SERVER_ANALYZE_DATA + "." + cacheDirName())
        ).toArray(String[]::new);
    }

    public String[] getPreTranscodeRecentlyWatchedQueues() {
        return config.getDirectories().stream()
                .map(dir -> APP_ISTER_SERVER_PRE_TRANSCODE_RECENTLY_WATCHED + "." + dir.getName())
                .toArray(String[]::new);
    }
}
