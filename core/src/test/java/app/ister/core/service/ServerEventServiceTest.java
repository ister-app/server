package app.ister.core.service;

import app.ister.core.eventdata.AlbumFoundData;
import app.ister.core.eventdata.ArtistFoundData;
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
    void createArtistFoundEventSendsCorrectData() {
        UUID artistId = UUID.randomUUID();

        subject.createArtistFoundEvent(artistId);

        ArgumentCaptor<ArtistFoundData> captor = ArgumentCaptor.forClass(ArtistFoundData.class);
        verify(messageSender).sendArtistFound(captor.capture());
        assertEquals(artistId, captor.getValue().getArtistId());
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
}
