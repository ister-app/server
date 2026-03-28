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

    // Worker → disk (directory-scoped, like sendMediaFileFound)
    public void sendAnalyzeData(AnalyzeData data, String directoryName) {
        String queue = APP_ISTER_SERVER_ANALYZE_DATA + "." + directoryName;
        log.debug("Sending message for queue: {} and analyzeData: {}", queue, data);
        rabbitTemplate.convertAndSend(queue, data);
    }
}
