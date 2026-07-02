package app.ister.core.service;

import app.ister.core.eventdata.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import static app.ister.core.MessageQueue.*;

@Service
@Slf4j
public class MessageSender {

    private final RabbitTemplate rabbitTemplate;

    public MessageSender(final RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** Sends a message to a directory/node-scoped queue ({@code baseQueue.suffix}). */
    private void send(String baseQueue, String suffix, MessageData data) {
        send(baseQueue + "." + suffix, data);
    }

    /** Sends a message to a queue; any consumer bound to it may pick it up. */
    private void send(String queue, MessageData data) {
        log.debug("Sending message for queue: {} and data: {}", queue, data);
        rabbitTemplate.convertAndSend(queue, data);
    }

    // disk module

    public void sendFileScanRequested(FileScanRequestedData fileScanRequestedData, String directoryName) {
        send(APP_ISTER_SERVER_FILE_SCAN_REQUESTED, directoryName, fileScanRequestedData);
    }

    public void sendImageFound(ImageFoundData imageFoundData, String directoryName) {
        send(APP_ISTER_SERVER_IMAGE_FOUND, directoryName, imageFoundData);
    }

    public void sendMediaFileFound(MediaFileFoundData mediaFileFoundData, String directoryName) {
        send(APP_ISTER_SERVER_MEDIA_FILE_FOUND, directoryName, mediaFileFoundData);
    }

    public void sendNewDirectoriesScanRequested(NewDirectoriesScanRequestedData newDirectoriesScanRequestedData, String directoryName) {
        send(APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED, directoryName, newDirectoriesScanRequestedData);
    }

    public void sendNfoFileFound(NfoFileFoundData nfoFileFoundData, String directoryName) {
        send(APP_ISTER_SERVER_NFO_FILE_FOUND, directoryName, nfoFileFoundData);
    }

    public void sendSubtitleFileFound(SubtitleFileFoundData subtitleFileFoundData, String directoryName) {
        send(APP_ISTER_SERVER_SUBTITLE_FILE_FOUND, directoryName, subtitleFileFoundData);
    }

    public void sendUpdateImagesRequested(UpdateImagesRequestedData updateImagesRequestedData, String directoryName) {
        send(APP_ISTER_SERVER_UPDATE_IMAGES_REQUESTED, directoryName, updateImagesRequestedData);
    }

    // worker module

    public void sendAnalyzeLibraryRequested(AnalyzeLibraryRequestedData analyzeLibraryRequestedData, String nodeName) {
        send(APP_ISTER_SERVER_ANALYZE_LIBRARY_REQUESTED, nodeName, analyzeLibraryRequestedData);
    }

    public void sendEpisodeFound(EpisodeFoundData episodeFoundData) {
        send(APP_ISTER_SERVER_EPISODE_FOUND, episodeFoundData);
    }

    public void sendMovieFound(MovieFoundData movieFoundData) {
        send(APP_ISTER_SERVER_MOVIE_FOUND, movieFoundData);
    }

    public void sendShowFound(ShowFoundData showFoundData) {
        send(APP_ISTER_SERVER_SHOW_FOUND, showFoundData);
    }

    // API → worker (no suffix, any worker can pick it up like sendMovieFound)
    public void sendAnalyzeData(AnalyzeData data) {
        send(APP_ISTER_SERVER_ANALYZE_DATA, data);
    }

    // transcoder module (directory-scoped, like sendMediaFileFound)
    public void sendTranscodeRequested(TranscodeRequestedData transcodeRequestedData, String directoryName) {
        send(APP_ISTER_SERVER_TRANSCODE_REQUESTED, directoryName, transcodeRequestedData);
    }

    public void sendTranscodePassRequested(TranscodePassRequestedData transcodePassRequestedData, String directoryName) {
        send(APP_ISTER_SERVER_TRANSCODE_PASS_REQUESTED, directoryName, transcodePassRequestedData);
    }

    public void sendPreTranscodeRecentlyWatched(PreTranscodeRecentlyWatchedData data, String diskName) {
        send(APP_ISTER_SERVER_PRE_TRANSCODE_RECENTLY_WATCHED, diskName, data);
    }

    public void sendArtistFound(ArtistFoundData artistFoundData) {
        send(APP_ISTER_SERVER_ARTIST_FOUND, artistFoundData);
    }

    public void sendArtistFound(ArtistFoundData artistFoundData, String nodeName) {
        send(APP_ISTER_SERVER_ARTIST_FOUND, nodeName, artistFoundData);
    }

    public void sendAlbumFound(AlbumFoundData albumFoundData) {
        send(APP_ISTER_SERVER_ALBUM_FOUND, albumFoundData);
    }

    public void sendAlbumFound(AlbumFoundData albumFoundData, String nodeName) {
        send(APP_ISTER_SERVER_ALBUM_FOUND, nodeName, albumFoundData);
    }

    public void sendTrackFound(TrackFoundData trackFoundData) {
        send(APP_ISTER_SERVER_TRACK_FOUND, trackFoundData);
    }

    public void sendAudioFileFound(AudioFileFoundData audioFileFoundData, String directoryName) {
        send(APP_ISTER_SERVER_AUDIO_FILE_FOUND, directoryName, audioFileFoundData);
    }

    // Worker → disk (directory-scoped, like sendMediaFileFound)
    public void sendAnalyzeData(AnalyzeData data, String directoryName) {
        send(APP_ISTER_SERVER_ANALYZE_DATA, directoryName, data);
    }
}
