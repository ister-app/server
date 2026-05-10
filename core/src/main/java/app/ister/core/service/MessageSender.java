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

    // disk module

    public void sendFileScanRequested(FileScanRequestedData fileScanRequestedData, String directoryName) {
        String queue = APP_ISTER_SERVER_FILE_SCAN_REQUESTED + "." + directoryName;
        log.debug("Sending message for queue: {} and fileScanRequestedData: {}", queue, fileScanRequestedData);
        rabbitTemplate.convertAndSend(queue, fileScanRequestedData);
    }

    public void sendImageFound(ImageFoundData imageFoundData, String directoryName) {
        String queue = APP_ISTER_SERVER_IMAGE_FOUND + "." + directoryName;
        log.debug("Sending message for queue: {} and showImageData: {}", queue, imageFoundData);
        rabbitTemplate.convertAndSend(queue, imageFoundData);
    }

    public void sendMediaFileFound(MediaFileFoundData mediaFileFoundData, String directoryName) {
        String queue = APP_ISTER_SERVER_MEDIA_FILE_FOUND + "." + directoryName;
        log.debug("Sending message for queue: {} and mediaFileFoundData: {}", queue, mediaFileFoundData);
        rabbitTemplate.convertAndSend(queue, mediaFileFoundData);
    }

    public void sendNewDirectoriesScanRequested(NewDirectoriesScanRequestedData newDirectoriesScanRequestedData, String directoryName) {
        String queue = APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED + "." + directoryName;
        log.debug("Sending message for queue: {} and newDirectoriesScanRequestedData: {}", queue, newDirectoriesScanRequestedData);
        rabbitTemplate.convertAndSend(queue, newDirectoriesScanRequestedData);
    }

    public void sendNfoFileFound(NfoFileFoundData nfoFileFoundData, String directoryName) {
        String queue = APP_ISTER_SERVER_NFO_FILE_FOUND + "." + directoryName;
        log.debug("Sending message for queue: {} and nfoFileFoundData: {}", queue, nfoFileFoundData);
        rabbitTemplate.convertAndSend(queue, nfoFileFoundData);
    }

    public void sendSubtitleFileFound(SubtitleFileFoundData subtitleFileFoundData, String directoryName) {
        String queue = APP_ISTER_SERVER_SUBTITLE_FILE_FOUND + "." + directoryName;
        log.debug("Sending message for queue: {} and subtitleFileFoundData: {}", queue, subtitleFileFoundData);
        rabbitTemplate.convertAndSend(queue, subtitleFileFoundData);
    }

    public void sendUpdateImagesRequested(UpdateImagesRequestedData updateImagesRequestedData, String directoryName) {
        String queue = APP_ISTER_SERVER_UPDATE_IMAGES_REQUESTED + "." + directoryName;
        log.debug("Sending message for queue: {} and updateImagesRequestedData: {}", queue, updateImagesRequestedData);
        rabbitTemplate.convertAndSend(queue, updateImagesRequestedData);
    }

    // worker module

    public void sendAnalyzeLibraryRequested(AnalyzeLibraryRequestedData analyzeLibraryRequestedData, String nodeName) {
        String queue = APP_ISTER_SERVER_ANALYZE_LIBRARY_REQUESTED + "." + nodeName;
        log.debug("Sending message for queue: {} and analyzeLibraryRequestedData: {}", queue, analyzeLibraryRequestedData);
        rabbitTemplate.convertAndSend(queue, analyzeLibraryRequestedData);
    }

    public void sendEpisodeFound(EpisodeFoundData episodeFoundData) {
        log.debug("Sending message for queue: {} and episodeFoundData: {}", APP_ISTER_SERVER_EPISODE_FOUND, episodeFoundData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_EPISODE_FOUND, episodeFoundData);
    }

    public void sendMovieFound(MovieFoundData movieFoundData) {
        log.debug("Sending message for queue: {} and movieFoundData: {}", APP_ISTER_SERVER_MOVIE_FOUND, movieFoundData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_MOVIE_FOUND, movieFoundData);
    }

    public void sendShowFound(ShowFoundData showFoundData) {
        log.debug("Sending message for queue: {} and showFoundData: {}", APP_ISTER_SERVER_SHOW_FOUND, showFoundData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_SHOW_FOUND, showFoundData);
    }

    // API → worker (no suffix, any worker can pick it up like sendMovieFound)
    public void sendAnalyzeData(AnalyzeData data) {
        log.debug("Sending message for queue: {} and analyzeData: {}", APP_ISTER_SERVER_ANALYZE_DATA, data);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_ANALYZE_DATA, data);
    }

    // transcoder module (directory-scoped, like sendMediaFileFound)
    public void sendTranscodeRequested(TranscodeRequestedData transcodeRequestedData, String directoryName) {
        String queue = APP_ISTER_SERVER_TRANSCODE_REQUESTED + "." + directoryName;
        log.debug("Sending message for queue: {} and transcodeRequestedData: {}", queue, transcodeRequestedData);
        rabbitTemplate.convertAndSend(queue, transcodeRequestedData);
    }

    public void sendTranscodePassRequested(TranscodePassRequestedData transcodePassRequestedData, String directoryName) {
        String queue = APP_ISTER_SERVER_TRANSCODE_PASS_REQUESTED + "." + directoryName;
        log.debug("Sending message for queue: {} and transcodePassRequestedData: {}", queue, transcodePassRequestedData);
        rabbitTemplate.convertAndSend(queue, transcodePassRequestedData);
    }

    public void sendPreTranscodeRecentlyWatched(PreTranscodeRecentlyWatchedData data, String diskName) {
        String queue = APP_ISTER_SERVER_PRE_TRANSCODE_RECENTLY_WATCHED + "." + diskName;
        log.debug("Sending message for queue: {}", queue);
        rabbitTemplate.convertAndSend(queue, data);
    }

    public void sendArtistFound(ArtistFoundData artistFoundData) {
        log.debug("Sending message for queue: {} and artistFoundData: {}", APP_ISTER_SERVER_ARTIST_FOUND, artistFoundData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_ARTIST_FOUND, artistFoundData);
    }

    public void sendArtistFound(ArtistFoundData artistFoundData, String nodeName) {
        String queue = APP_ISTER_SERVER_ARTIST_FOUND + "." + nodeName;
        log.debug("Sending message for queue: {} and artistFoundData: {}", queue, artistFoundData);
        rabbitTemplate.convertAndSend(queue, artistFoundData);
    }

    public void sendAlbumFound(AlbumFoundData albumFoundData) {
        log.debug("Sending message for queue: {} and albumFoundData: {}", APP_ISTER_SERVER_ALBUM_FOUND, albumFoundData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_ALBUM_FOUND, albumFoundData);
    }

    public void sendAlbumFound(AlbumFoundData albumFoundData, String nodeName) {
        String queue = APP_ISTER_SERVER_ALBUM_FOUND + "." + nodeName;
        log.debug("Sending message for queue: {} and albumFoundData: {}", queue, albumFoundData);
        rabbitTemplate.convertAndSend(queue, albumFoundData);
    }

    public void sendTrackFound(TrackFoundData trackFoundData) {
        log.debug("Sending message for queue: {} and trackFoundData: {}", APP_ISTER_SERVER_TRACK_FOUND, trackFoundData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_TRACK_FOUND, trackFoundData);
    }

    public void sendAudioFileFound(AudioFileFoundData audioFileFoundData, String directoryName) {
        String queue = APP_ISTER_SERVER_AUDIO_FILE_FOUND + "." + directoryName;
        log.debug("Sending message for queue: {} and audioFileFoundData: {}", queue, audioFileFoundData);
        rabbitTemplate.convertAndSend(queue, audioFileFoundData);
    }

    // Worker → disk (directory-scoped, like sendMediaFileFound)
    public void sendAnalyzeData(AnalyzeData data, String directoryName) {
        String queue = APP_ISTER_SERVER_ANALYZE_DATA + "." + directoryName;
        log.debug("Sending message for queue: {} and analyzeData: {}", queue, data);
        rabbitTemplate.convertAndSend(queue, data);
    }
}
