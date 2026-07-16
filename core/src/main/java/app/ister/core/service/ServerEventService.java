package app.ister.core.service;

import app.ister.core.enums.EventType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.eventdata.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ServerEventService {
    private final MessageSender messageSender;

    public void createMovieFoundEvent(UUID movieId) {
        publishAfterCommit(() -> messageSender.sendMovieFound(MovieFoundData.builder()
                        .eventType(EventType.MOVIE_FOUND)
                        .movieId(movieId)
                        .build()));
        createSearchIndexEvent(SearchEntityType.MOVIE, movieId);
    }

    public void createShowFoundEvent(UUID showId) {
        publishAfterCommit(() -> messageSender.sendShowFound(ShowFoundData.builder()
                        .eventType(EventType.SHOW_FOUND)
                        .showId(showId)
                        .build()));
        createSearchIndexEvent(SearchEntityType.SHOW, showId);
    }

    public void createEpisodeFoundEvent(UUID episodeId) {
        publishAfterCommit(() -> messageSender.sendEpisodeFound(EpisodeFoundData.builder()
                        .eventType(EventType.EPISODE_FOUND)
                        .episodeId(episodeId)
                        .build()));
        createSearchIndexEvent(SearchEntityType.EPISODE, episodeId);
    }

    public void createPersonFoundEvent(UUID personId) {
        publishAfterCommit(() -> messageSender.sendPersonFound(PersonFoundData.builder()
                        .eventType(EventType.PERSON_FOUND)
                        .personId(personId)
                        .build()));
        createSearchIndexEvent(SearchEntityType.PERSON, personId);
    }

    public void createAlbumFoundEvent(UUID albumId) {
        publishAfterCommit(() -> messageSender.sendAlbumFound(AlbumFoundData.builder()
                        .eventType(EventType.ALBUM_FOUND)
                        .albumId(albumId)
                        .build()));
        createSearchIndexEvent(SearchEntityType.ALBUM, albumId);
    }

    public void createBookFoundEvent(UUID bookId) {
        publishAfterCommit(() -> messageSender.sendBookFound(BookFoundData.builder()
                        .eventType(EventType.BOOK_FOUND)
                        .bookId(bookId)
                        .build()));
        createSearchIndexEvent(SearchEntityType.BOOK, bookId);
    }

    public void createComicSeriesFoundEvent(UUID seriesId) {
        publishAfterCommit(() -> messageSender.sendComicSeriesFound(ComicSeriesFoundData.builder()
                        .eventType(EventType.COMIC_SERIES_FOUND)
                        .seriesId(seriesId)
                        .build()));
    }

    public void createChapterFoundEvent(UUID chapterId) {
        publishAfterCommit(() -> messageSender.sendChapterFound(ChapterFoundData.builder()
                        .eventType(EventType.CHAPTER_FOUND)
                        .chapterId(chapterId)
                        .build()));
    }

    public void createTrackFoundEvent(UUID trackId) {
        publishAfterCommit(() -> messageSender.sendTrackFound(TrackFoundData.builder()
                        .eventType(EventType.TRACK_FOUND)
                        .trackId(trackId)
                        .build()));
        createSearchIndexEvent(SearchEntityType.TRACK, trackId);
    }

    public void createPodcastFoundEvent(UUID podcastId) {
        publishAfterCommit(() -> messageSender.sendPodcastFound(PodcastFoundData.builder()
                        .eventType(EventType.PODCAST_FOUND)
                        .podcastId(podcastId)
                        .build()));
        createSearchIndexEvent(SearchEntityType.PODCAST, podcastId);
    }

    public void createPodcastEpisodeFoundEvent(UUID podcastEpisodeId) {
        publishAfterCommit(() -> messageSender.sendPodcastEpisodeFound(PodcastEpisodeFoundData.builder()
                        .eventType(EventType.PODCAST_EPISODE_FOUND)
                        .podcastEpisodeId(podcastEpisodeId)
                        .build()));
    }

    public void createSearchIndexEvent(SearchEntityType entityType, UUID entityId) {
        sendSearchIndexEvent(entityType, entityId, SearchIndexRequestedData.Action.UPSERT);
    }

    public void createSearchDeleteEvent(SearchEntityType entityType, UUID entityId) {
        sendSearchIndexEvent(entityType, entityId, SearchIndexRequestedData.Action.DELETE);
    }

    private void sendSearchIndexEvent(SearchEntityType entityType, UUID entityId, SearchIndexRequestedData.Action action) {
        publishAfterCommit(() -> messageSender.sendSearchIndexRequested(SearchIndexRequestedData.builder()
                        .eventType(EventType.SEARCH_INDEX_REQUESTED)
                        .entityType(entityType)
                        .entityId(entityId)
                        .action(action)
                        .build()));
    }

    public void createSearchReindexEvent() {
        publishAfterCommit(() -> messageSender.sendSearchReindexRequested(SearchReindexRequestedData.builder()
                        .eventType(EventType.SEARCH_REINDEX_REQUESTED)
                        .build()));
    }

    /**
     * Publishes after the surrounding transaction commits (immediately when there is none).
     * Every event here carries an entity id the consumer looks up, so publishing
     * mid-transaction — the scan runs in one long transaction — races the commit: the
     * consumer reads before the row is visible and skips silently or dead-letters, flakily.
     */
    private void publishAfterCommit(Runnable publish) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
