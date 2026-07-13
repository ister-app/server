package app.ister.core.service;

import app.ister.core.enums.EventType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.AlbumFoundData;
import app.ister.core.eventdata.BookFoundData;
import app.ister.core.eventdata.ChapterFoundData;
import app.ister.core.eventdata.PodcastEpisodeFoundData;
import app.ister.core.eventdata.PodcastFoundData;
import app.ister.core.eventdata.SearchIndexRequestedData;
import app.ister.core.eventdata.SearchReindexRequestedData;
import app.ister.core.eventdata.PersonFoundData;
import app.ister.core.eventdata.EpisodeFoundData;
import app.ister.core.eventdata.MovieFoundData;
import app.ister.core.eventdata.ShowFoundData;
import app.ister.core.eventdata.TrackFoundData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ServerEventServiceTest {

    @InjectMocks
    private ServerEventService subject;

    @Mock
    private MessageSender messageSender;

    @Test
    void createMovieFoundEventSendsCorrectData() {
        UUID movieId = UUID.randomUUID();

        subject.createMovieFoundEvent(movieId);

        ArgumentCaptor<MovieFoundData> captor = ArgumentCaptor.forClass(MovieFoundData.class);
        verify(messageSender).sendMovieFound(captor.capture());
        assertEquals(movieId, captor.getValue().getMovieId());
    }

    @Test
    void createShowFoundEventSendsCorrectData() {
        UUID showId = UUID.randomUUID();

        subject.createShowFoundEvent(showId);

        ArgumentCaptor<ShowFoundData> captor = ArgumentCaptor.forClass(ShowFoundData.class);
        verify(messageSender).sendShowFound(captor.capture());
        assertEquals(showId, captor.getValue().getShowId());
    }

    @Test
    void createEpisodeFoundEventSendsCorrectData() {
        UUID episodeId = UUID.randomUUID();

        subject.createEpisodeFoundEvent(episodeId);

        ArgumentCaptor<EpisodeFoundData> captor = ArgumentCaptor.forClass(EpisodeFoundData.class);
        verify(messageSender).sendEpisodeFound(captor.capture());
        assertEquals(episodeId, captor.getValue().getEpisodeId());
    }

    @Test
    void createPersonFoundEventSendsCorrectData() {
        UUID personId = UUID.randomUUID();

        subject.createPersonFoundEvent(personId);

        ArgumentCaptor<PersonFoundData> captor = ArgumentCaptor.forClass(PersonFoundData.class);
        verify(messageSender).sendPersonFound(captor.capture());
        assertEquals(personId, captor.getValue().getPersonId());
    }

    @Test
    void createAlbumFoundEventSendsCorrectData() {
        UUID albumId = UUID.randomUUID();

        subject.createAlbumFoundEvent(albumId);

        ArgumentCaptor<AlbumFoundData> captor = ArgumentCaptor.forClass(AlbumFoundData.class);
        verify(messageSender).sendAlbumFound(captor.capture());
        assertEquals(albumId, captor.getValue().getAlbumId());
    }

    @Test
    void createTrackFoundEventSendsCorrectData() {
        UUID trackId = UUID.randomUUID();

        subject.createTrackFoundEvent(trackId);

        ArgumentCaptor<TrackFoundData> captor = ArgumentCaptor.forClass(TrackFoundData.class);
        verify(messageSender).sendTrackFound(captor.capture());
        assertEquals(trackId, captor.getValue().getTrackId());
    }

    @Test
    void createBookFoundEventSendsCorrectData() {
        UUID bookId = UUID.randomUUID();

        subject.createBookFoundEvent(bookId);

        ArgumentCaptor<BookFoundData> captor = ArgumentCaptor.forClass(BookFoundData.class);
        verify(messageSender).sendBookFound(captor.capture());
        assertEquals(bookId, captor.getValue().getBookId());
        assertEquals(SearchEntityType.BOOK, captureSearchIndexEvent().getEntityType());
    }

    /** Chapters are not searchable on their own, so no index event is emitted. */
    @Test
    void createChapterFoundEventSendsNoSearchIndexEvent() {
        UUID chapterId = UUID.randomUUID();

        subject.createChapterFoundEvent(chapterId);

        ArgumentCaptor<ChapterFoundData> captor = ArgumentCaptor.forClass(ChapterFoundData.class);
        verify(messageSender).sendChapterFound(captor.capture());
        assertEquals(chapterId, captor.getValue().getChapterId());
        verify(messageSender, never()).sendSearchIndexRequested(any());
    }

    @Test
    void createPodcastFoundEventSendsCorrectData() {
        UUID podcastId = UUID.randomUUID();

        subject.createPodcastFoundEvent(podcastId);

        ArgumentCaptor<PodcastFoundData> captor = ArgumentCaptor.forClass(PodcastFoundData.class);
        verify(messageSender).sendPodcastFound(captor.capture());
        assertEquals(podcastId, captor.getValue().getPodcastId());
        assertEquals(SearchEntityType.PODCAST, captureSearchIndexEvent().getEntityType());
    }

    @Test
    void createPodcastEpisodeFoundEventSendsNoSearchIndexEvent() {
        UUID episodeId = UUID.randomUUID();

        subject.createPodcastEpisodeFoundEvent(episodeId);

        ArgumentCaptor<PodcastEpisodeFoundData> captor = ArgumentCaptor.forClass(PodcastEpisodeFoundData.class);
        verify(messageSender).sendPodcastEpisodeFound(captor.capture());
        assertEquals(episodeId, captor.getValue().getPodcastEpisodeId());
        verify(messageSender, never()).sendSearchIndexRequested(any());
    }

    @Test
    void createMovieFoundEventAlsoIndexesTheMovie() {
        UUID movieId = UUID.randomUUID();

        subject.createMovieFoundEvent(movieId);

        SearchIndexRequestedData data = captureSearchIndexEvent();
        assertEquals(SearchEntityType.MOVIE, data.getEntityType());
        assertEquals(movieId, data.getEntityId());
        assertEquals(SearchIndexRequestedData.Action.UPSERT, data.getAction());
    }

    @Test
    void createSearchDeleteEventSendsADeleteAction() {
        UUID showId = UUID.randomUUID();

        subject.createSearchDeleteEvent(SearchEntityType.SHOW, showId);

        SearchIndexRequestedData data = captureSearchIndexEvent();
        assertEquals(SearchEntityType.SHOW, data.getEntityType());
        assertEquals(showId, data.getEntityId());
        assertEquals(SearchIndexRequestedData.Action.DELETE, data.getAction());
    }

    @Test
    void createSearchReindexEventSendsAReindexRequest() {
        subject.createSearchReindexEvent();

        ArgumentCaptor<SearchReindexRequestedData> captor = ArgumentCaptor.forClass(SearchReindexRequestedData.class);
        verify(messageSender).sendSearchReindexRequested(captor.capture());
        assertEquals(EventType.SEARCH_REINDEX_REQUESTED, captor.getValue().getEventType());
    }

    private SearchIndexRequestedData captureSearchIndexEvent() {
        ArgumentCaptor<SearchIndexRequestedData> captor = ArgumentCaptor.forClass(SearchIndexRequestedData.class);
        verify(messageSender).sendSearchIndexRequested(captor.capture());
        return captor.getValue();
    }
}
