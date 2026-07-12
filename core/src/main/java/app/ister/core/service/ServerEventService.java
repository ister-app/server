package app.ister.core.service;

import app.ister.core.enums.EventType;
import app.ister.core.enums.SearchEntityType;
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
        createSearchIndexEvent(SearchEntityType.MOVIE, movieId);
    }

    public void createShowFoundEvent(UUID showId) {
        messageSender.sendShowFound(ShowFoundData.builder()
                .eventType(EventType.SHOW_FOUND)
                .showId(showId)
                .build());
        createSearchIndexEvent(SearchEntityType.SHOW, showId);
    }

    public void createEpisodeFoundEvent(UUID episodeId) {
        messageSender.sendEpisodeFound(EpisodeFoundData.builder()
                .eventType(EventType.EPISODE_FOUND)
                .episodeId(episodeId)
                .build());
        createSearchIndexEvent(SearchEntityType.EPISODE, episodeId);
    }

    public void createPersonFoundEvent(UUID personId) {
        messageSender.sendPersonFound(PersonFoundData.builder()
                .eventType(EventType.PERSON_FOUND)
                .personId(personId)
                .build());
        createSearchIndexEvent(SearchEntityType.PERSON, personId);
    }

    public void createAlbumFoundEvent(UUID albumId) {
        messageSender.sendAlbumFound(AlbumFoundData.builder()
                .eventType(EventType.ALBUM_FOUND)
                .albumId(albumId)
                .build());
        createSearchIndexEvent(SearchEntityType.ALBUM, albumId);
    }

    public void createBookFoundEvent(UUID bookId) {
        messageSender.sendBookFound(BookFoundData.builder()
                .eventType(EventType.BOOK_FOUND)
                .bookId(bookId)
                .build());
        createSearchIndexEvent(SearchEntityType.BOOK, bookId);
    }

    public void createChapterFoundEvent(UUID chapterId) {
        messageSender.sendChapterFound(ChapterFoundData.builder()
                .eventType(EventType.CHAPTER_FOUND)
                .chapterId(chapterId)
                .build());
    }

    public void createTrackFoundEvent(UUID trackId) {
        messageSender.sendTrackFound(TrackFoundData.builder()
                .eventType(EventType.TRACK_FOUND)
                .trackId(trackId)
                .build());
        createSearchIndexEvent(SearchEntityType.TRACK, trackId);
    }

    public void createPodcastFoundEvent(UUID podcastId) {
        messageSender.sendPodcastFound(PodcastFoundData.builder()
                .eventType(EventType.PODCAST_FOUND)
                .podcastId(podcastId)
                .build());
        createSearchIndexEvent(SearchEntityType.PODCAST, podcastId);
    }

    public void createPodcastEpisodeFoundEvent(UUID podcastEpisodeId) {
        messageSender.sendPodcastEpisodeFound(PodcastEpisodeFoundData.builder()
                .eventType(EventType.PODCAST_EPISODE_FOUND)
                .podcastEpisodeId(podcastEpisodeId)
                .build());
    }

    public void createSearchIndexEvent(SearchEntityType entityType, UUID entityId) {
        sendSearchIndexEvent(entityType, entityId, SearchIndexRequestedData.Action.UPSERT);
    }

    public void createSearchDeleteEvent(SearchEntityType entityType, UUID entityId) {
        sendSearchIndexEvent(entityType, entityId, SearchIndexRequestedData.Action.DELETE);
    }

    private void sendSearchIndexEvent(SearchEntityType entityType, UUID entityId, SearchIndexRequestedData.Action action) {
        messageSender.sendSearchIndexRequested(SearchIndexRequestedData.builder()
                .eventType(EventType.SEARCH_INDEX_REQUESTED)
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .build());
    }

    public void createSearchReindexEvent() {
        messageSender.sendSearchReindexRequested(SearchReindexRequestedData.builder()
                .eventType(EventType.SEARCH_REINDEX_REQUESTED)
                .build());
    }
}
