package app.ister.core.service;

import app.ister.core.config.StatusExchangeConfig;
import app.ister.core.eventdata.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static app.ister.core.MessageQueue.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MessageSenderTest {
    @Mock
    private RabbitTemplate rabbitTemplateMock;

    private MessageSender subject;

    @BeforeEach
    void setUp() {
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

    @Test
    void sendAnalyzeData() {
        AnalyzeData data = AnalyzeData.builder().build();
        subject.sendAnalyzeData(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_ANALYZE_DATA, data);
    }

    @Test
    void sendAnalyzeDataWithDirectory() {
        AnalyzeData data = AnalyzeData.builder().build();
        subject.sendAnalyzeData(data, "disk1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_ANALYZE_DATA + ".disk1", data);
    }

    @Test
    void sendTranscodeRequested() {
        TranscodeRequestedData data = TranscodeRequestedData.builder().build();
        subject.sendTranscodeRequested(data, "disk1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_TRANSCODE_REQUESTED + ".disk1", data);
    }

    @Test
    void sendTranscodePassRequested() {
        TranscodePassRequestedData data = TranscodePassRequestedData.builder().build();
        subject.sendTranscodePassRequested(data, "disk1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_TRANSCODE_PASS_REQUESTED + ".disk1", data);
    }

    @Test
    void sendPreTranscodeRecentlyWatched() {
        PreTranscodeRecentlyWatchedData data = PreTranscodeRecentlyWatchedData.builder().build();
        subject.sendPreTranscodeRecentlyWatched(data, "disk1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_PRE_TRANSCODE_RECENTLY_WATCHED + ".disk1", data);
    }

    @Test
    void sendPersonFoundWithoutNode() {
        PersonFoundData data = PersonFoundData.builder().build();
        subject.sendPersonFound(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_PERSON_FOUND, data);
    }

    @Test
    void sendPersonFoundWithNode() {
        PersonFoundData data = PersonFoundData.builder().build();
        subject.sendPersonFound(data, "node1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_PERSON_FOUND + ".node1", data);
    }

    @Test
    void sendAlbumFoundWithoutNode() {
        AlbumFoundData data = AlbumFoundData.builder().build();
        subject.sendAlbumFound(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_ALBUM_FOUND, data);
    }

    @Test
    void sendAlbumFoundWithNode() {
        AlbumFoundData data = AlbumFoundData.builder().build();
        subject.sendAlbumFound(data, "node1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_ALBUM_FOUND + ".node1", data);
    }

    @Test
    void sendTrackFound() {
        TrackFoundData data = TrackFoundData.builder().build();
        subject.sendTrackFound(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_TRACK_FOUND, data);
    }

    @Test
    void sendAudioFileFound() {
        AudioFileFoundData data = AudioFileFoundData.builder().build();
        subject.sendAudioFileFound(data, "disk1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_AUDIO_FILE_FOUND + ".disk1", data);
    }

    @Test
    void sendBookFound() {
        BookFoundData data = BookFoundData.builder().build();
        subject.sendBookFound(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_BOOK_FOUND, data);
    }

    @Test
    void sendChapterFound() {
        ChapterFoundData data = ChapterFoundData.builder().build();
        subject.sendChapterFound(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_CHAPTER_FOUND, data);
    }

    @Test
    void sendEpubFileFound() {
        EpubFileFoundData data = EpubFileFoundData.builder().build();
        subject.sendEpubFileFound(data, "disk1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_EPUB_FILE_FOUND + ".disk1", data);
    }

    @Test
    void sendPodcastFound() {
        PodcastFoundData data = PodcastFoundData.builder().build();
        subject.sendPodcastFound(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_PODCAST_FOUND, data);
    }

    @Test
    void sendPodcastEpisodeFound() {
        PodcastEpisodeFoundData data = PodcastEpisodeFoundData.builder().build();
        subject.sendPodcastEpisodeFound(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_PODCAST_EPISODE_FOUND, data);
    }

    @Test
    void sendPodcastRefreshRequested() {
        PodcastRefreshRequestedData data = PodcastRefreshRequestedData.builder().build();
        subject.sendPodcastRefreshRequested(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_PODCAST_REFRESH_REQUESTED, data);
    }

    @Test
    void sendPodcastEpisodeDownloadRequested() {
        PodcastEpisodeDownloadRequestedData data = PodcastEpisodeDownloadRequestedData.builder().build();
        subject.sendPodcastEpisodeDownloadRequested(data, "cache1");
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_PODCAST_EPISODE_DOWNLOAD_REQUESTED + ".cache1", data);
    }

    @Test
    void sendSearchIndexRequested() {
        SearchIndexRequestedData data = SearchIndexRequestedData.builder().build();
        subject.sendSearchIndexRequested(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_SEARCH_INDEX_REQUESTED, data);
    }

    @Test
    void sendSearchReindexRequested() {
        SearchReindexRequestedData data = SearchReindexRequestedData.builder().build();
        subject.sendSearchReindexRequested(data);
        verify(rabbitTemplateMock).convertAndSend(APP_ISTER_SERVER_SEARCH_REINDEX_REQUESTED, data);
    }

    /** Status messages go to the fan-out exchange, not to a named queue. */
    @Test
    void sendStatusPublishesOnTheStatusExchange() {
        PlaybackStatusData data = PlaybackStatusData.builder().build();
        subject.sendStatus(data);
        verify(rabbitTemplateMock).convertAndSend(StatusExchangeConfig.STATUS_EXCHANGE, "", (Object) data);
    }
}