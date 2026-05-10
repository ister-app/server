package app.ister.core.service;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ServerEventService {
    private final MessageSender messageSender;

    public void createMovieFoundEvent(UUID movieId) {
        messageSender.sendMovieFound(MovieFoundData.builder()
                .eventType(EventType.MOVIE_FOUND)
                .movieId(movieId)
                .build());
    }

    public void createShowFoundEvent(UUID showId) {
        messageSender.sendShowFound(ShowFoundData.builder()
                .eventType(EventType.SHOW_FOUND)
                .showId(showId)
                .build());
    }

    public void createEpisodeFoundEvent(UUID episodeId) {
        messageSender.sendEpisodeFound(EpisodeFoundData.builder()
                .eventType(EventType.EPISODE_FOUND)
                .episodeId(episodeId)
                .build());
    }

    public void createArtistFoundEvent(UUID artistId) {
        messageSender.sendArtistFound(ArtistFoundData.builder()
                .eventType(EventType.ARTIST_FOUND)
                .artistId(artistId)
                .build());
    }

    public void createAlbumFoundEvent(UUID albumId) {
        messageSender.sendAlbumFound(AlbumFoundData.builder()
                .eventType(EventType.ALBUM_FOUND)
                .albumId(albumId)
                .build());
    }

    public void createTrackFoundEvent(UUID trackId) {
        messageSender.sendTrackFound(TrackFoundData.builder()
                .eventType(EventType.TRACK_FOUND)
                .trackId(trackId)
                .build());
    }
}
