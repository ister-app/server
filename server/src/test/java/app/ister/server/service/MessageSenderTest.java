package app.ister.server.service;

import app.ister.server.events.episodefound.EpisodeFoundData;
import app.ister.server.events.mediafilefound.MediaFileFoundData;
import app.ister.server.events.newdirectoriesscanrequested.NewDirectoriesScanRequestedData;
import app.ister.server.events.nfofilefound.NfoFileFoundData;
import app.ister.server.events.showfound.ShowFoundData;
import app.ister.server.events.subtitlefilefound.SubtitleFileFoundData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;

import static app.ister.server.events.MessageQueue.APP_ISTER_SERVER_EPISODE_FOUND;
import static app.ister.server.events.MessageQueue.APP_ISTER_SERVER_MEDIA_FILE_FOUND;
import static app.ister.server.events.MessageQueue.APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED;
import static app.ister.server.events.MessageQueue.APP_ISTER_SERVER_NFO_FILE_FOUND;
import static app.ister.server.events.MessageQueue.APP_ISTER_SERVER_SHOW_FOUND;
import static app.ister.server.events.MessageQueue.APP_ISTER_SERVER_SUBTITLE_FILE_FOUND;
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
    void sendMediaFileFound() {
        MediaFileFoundData data = MediaFileFoundData.builder().build();
        subject.sendMediaFileFound(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_MEDIA_FILE_FOUND, data);
    }

    @Test
    void sendNewDirectoriesScanRequested() {
        NewDirectoriesScanRequestedData data = NewDirectoriesScanRequestedData.builder().build();
        subject.sendNewDirectoriesScanRequested(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_NEW_DIRECTORIES_SCAN_REQUESTED, data);
    }

    @Test
    void sendNfoFileFound() {
        NfoFileFoundData data = NfoFileFoundData.builder().build();
        subject.sendNfoFileFound(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_NFO_FILE_FOUND, data);
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
        subject.sendSubtitleFileFound(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_SUBTITLE_FILE_FOUND, data);
    }
}