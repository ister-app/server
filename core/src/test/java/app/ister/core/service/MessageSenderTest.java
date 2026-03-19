package app.ister.core.service;

import app.ister.core.eventdata.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;

import static app.ister.core.MessageQueue.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MessageSenderTest {
    @Mock
    private RabbitTemplate rabbitTemplateMock;

    private MessageSender subject;

    @BeforeEach
    void setUp() throws IOException {
        subject = new MessageSender(rabbitTemplateMock);
    }

    @Test
    void sendEpisodeFound() {
        EpisodeFoundData data = EpisodeFoundData.builder().build();
        subject.sendEpisodeFound(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_EPISODE_FOUND, data);
    }

    @Test
    void sendFileScanRequested() {
        FileScanRequestedData data = FileScanRequestedData.builder().build();
        subject.sendFileScanRequested(data, "disk1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_FILE_SCAN_REQUESTED + ".disk1", data);
    }

    @Test
    void sendMediaFileFound() {
        MediaFileFoundData data = MediaFileFoundData.builder().build();
        subject.sendMediaFileFound(data, "disk1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_MEDIA_FILE_FOUND + ".disk1", data);
    }

    @Test
    void sendNewDirectoriesScanRequested() {
        NewDirectoriesScanRequestedData data = NewDirectoriesScanRequestedData.builder().build();
        subject.sendNewDirectoriesScanRequested(data, "disk1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED + ".disk1", data);
    }

    @Test
    void sendNfoFileFound() {
        NfoFileFoundData data = NfoFileFoundData.builder().build();
        subject.sendNfoFileFound(data, "disk1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_NFO_FILE_FOUND + ".disk1", data);
    }

    @Test
    void sendShowFound() {
        ShowFoundData data = ShowFoundData.builder().build();
        subject.sendShowFound(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_SHOW_FOUND, data);
    }

    @Test
    void sendSubtitleFileFound() {
        SubtitleFileFoundData data = SubtitleFileFoundData.builder().build();
        subject.sendSubtitleFileFound(data, "disk1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_SUBTITLE_FILE_FOUND + ".disk1", data);
    }

    @Test
    void sendImageFound() {
        ImageFoundData data = ImageFoundData.builder().build();
        subject.sendImageFound(data, "disk1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_IMAGE_FOUND + ".disk1", data);
    }

    @Test
    void sendMovieFound() {
        MovieFoundData data = MovieFoundData.builder().build();
        subject.sendMovieFound(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_MOVIE_FOUND, data);
    }

    @Test
    void sendAnalyzeLibraryRequested() {
        AnalyzeLibraryRequestedData data = AnalyzeLibraryRequestedData.builder().build();
        subject.sendAnalyzeLibraryRequested(data, "node1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_ANALYZE_LIBRARY_REQUESTED + ".node1", data);
    }

    @Test
    void sendUpdateImagesRequested() {
        UpdateImagesRequestedData data = UpdateImagesRequestedData.builder().build();
        subject.sendUpdateImagesRequested(data, "disk1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_UPDATE_IMAGES_REQUESTED + ".disk1", data);
    }
}