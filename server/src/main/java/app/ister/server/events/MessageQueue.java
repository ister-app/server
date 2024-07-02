package app.ister.server.events;

public class MessageQueue {

    public static final String APP_ISTER_SERVER_EPISODE_FOUND = "app.ister.server.EpisodeFound";
    public static final String APP_ISTER_SERVER_FILE_SCAN_REQUESTED = "app.ister.server.FileScanRequested";
    public static final String APP_ISTER_SERVER_MEDIA_FILE_FOUND = "app.ister.server.MediaFileFound";
    public static final String APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED = "app.ister.server.NewDirectoriesScanRequested";
    public static final String APP_ISTER_SERVER_NFO_FILE_FOUND = "app.ister.server.NfoFileFound";
    public static final String APP_ISTER_SERVER_SHOW_FOUND = "app.ister.server.ShowFound";
    public static final String APP_ISTER_SERVER_SUBTITLE_FILE_FOUND = "app.ister.server.SubtitleFileFound";

    private MessageQueue() {
        throw new IllegalStateException("Utility class");
    }

}
