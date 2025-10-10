package app.ister.server.service;

import app.ister.server.events.analyzelibraryrequested.AnalyzeLibraryRequestedData;
import app.ister.server.events.episodefound.EpisodeFoundData;
import app.ister.server.events.filescanrequested.FileScanRequestedData;
import app.ister.server.events.imagefound.ImageFoundData;
import app.ister.server.events.mediafilefound.MediaFileFoundData;
import app.ister.server.events.moviefound.MovieFoundData;
import app.ister.server.events.newdirectoriesscanrequested.NewDirectoriesScanRequestedData;
import app.ister.server.events.nfofilefound.NfoFileFoundData;
import app.ister.server.events.showfound.ShowFoundData;
import app.ister.server.events.subtitlefilefound.SubtitleFileFoundData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import static app.ister.server.events.MessageQueue.*;

@Service
@Slf4j
public class MessageSender {

    private final RabbitTemplate rabbitTemplate;

    public MessageSender(final RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void sendEpisodeFound(EpisodeFoundData episodeFoundData) {
        log.info("Sending message for queue: {} and episodeFoundData: {}", APP_ISTER_SERVER_EPISODE_FOUND, episodeFoundData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_EPISODE_FOUND, episodeFoundData);
    }

    public void sendFileScanRequested(FileScanRequestedData fileScanRequestedData) {
        log.info("Sending message for queue: {} and fileScanRequestedData: {}", APP_ISTER_SERVER_FILE_SCAN_REQUESTED, fileScanRequestedData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_FILE_SCAN_REQUESTED, fileScanRequestedData);
    }

    public void sendMediaFileFound(MediaFileFoundData mediaFileFoundData) {
        log.info("Sending message for queue: {} and mediaFileFoundData: {}", APP_ISTER_SERVER_MEDIA_FILE_FOUND, mediaFileFoundData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_MEDIA_FILE_FOUND, mediaFileFoundData);
    }

    public void sendMovieFound(MovieFoundData movieFoundData) {
        log.info("Sending message for queue: {} and movieFoundData: {}", APP_ISTER_SERVER_MOVIE_FOUND, movieFoundData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_MOVIE_FOUND, movieFoundData);
    }

    public void sendNewDirectoriesScanRequested(NewDirectoriesScanRequestedData newDirectoriesScanRequestedData) {
        log.info("Sending message for queue: {} and newDirectoriesScanRequestedData: {}", APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED, newDirectoriesScanRequestedData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED, newDirectoriesScanRequestedData);
    }

    public void sendAnalyzeLibraryRequested(AnalyzeLibraryRequestedData analyzeLibraryRequestedData) {
        log.info("Sending message for queue: {} and analyzeLibraryRequestedData: {}", APP_ISTER_SERVER_ANALYZE_LIBRARY_REQUESTED, analyzeLibraryRequestedData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_ANALYZE_LIBRARY_REQUESTED, analyzeLibraryRequestedData);
    }

    public void sendNfoFileFound(NfoFileFoundData nfoFileFoundData) {
        log.info("Sending message for queue: {} and nfoFileFoundData: {}", APP_ISTER_SERVER_NFO_FILE_FOUND, nfoFileFoundData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_NFO_FILE_FOUND, nfoFileFoundData);
    }

    public void sendShowFound(ShowFoundData showFoundData) {
        log.info("Sending message for queue: {} and showFoundData: {}", APP_ISTER_SERVER_SHOW_FOUND, showFoundData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_SHOW_FOUND, showFoundData);
    }

    public void sendSubtitleFileFound(SubtitleFileFoundData subtitleFileFoundData) {
        log.info("Sending message for queue: {} and subtitleFileFoundData: {}", APP_ISTER_SERVER_SUBTITLE_FILE_FOUND, subtitleFileFoundData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_SUBTITLE_FILE_FOUND, subtitleFileFoundData);
    }

    public void sendImageFound(ImageFoundData imageFoundData) {
        log.info("Sending message for queue: {} and showImageData: {}", APP_ISTER_SERVER_IMAGE_FOUND, imageFoundData);
        rabbitTemplate.convertAndSend(APP_ISTER_SERVER_IMAGE_FOUND, imageFoundData);
    }
}
