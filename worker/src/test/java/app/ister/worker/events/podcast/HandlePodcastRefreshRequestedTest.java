package app.ister.worker.events.podcast;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.PodcastEpisodeDownloadRequestedData;
import app.ister.core.eventdata.PodcastRefreshRequestedData;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ServerEventService;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandlePodcastRefreshRequestedTest {

    @Mock
    private PodcastRepository podcastRepository;
    @Mock
    private PodcastEpisodeRepository podcastEpisodeRepository;
    @Mock
    private MetadataRepository metadataRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private RssFeedParser rssFeedParser;
    @Mock
    private ImageDownloadService imageDownloadService;
    @Mock
    private ServerEventService serverEventService;
    @Mock
    private MessageSender messageSender;

    private HandlePodcastRefreshRequested subject;

    private PodcastEntity podcast;

    @BeforeEach
    void setUp() {
        subject = new HandlePodcastRefreshRequested(podcastRepository, podcastEpisodeRepository,
                metadataRepository, imageRepository, mediaFileRepository, rssFeedParser,
                imageDownloadService, serverEventService, messageSender);
        ReflectionTestUtils.setField(subject, "nodeName", "node1");
        ReflectionTestUtils.setField(subject, "autoDownloadCount", 2);

        podcast = PodcastEntity.builder()
                .libraryEntity(LibraryEntity.builder().name("podcasts").build())
                .feedUrl("https://example.org/feed.xml")
                .title("placeholder")
                .active(true)
                .build();
        podcast.setId(UUID.randomUUID());
        lenient().when(podcastRepository.findById(podcast.getId())).thenReturn(Optional.of(podcast));
    }

    private PodcastRefreshRequestedData event() {
        return PodcastRefreshRequestedData.builder()
                .eventType(EventType.PODCAST_REFRESH_REQUESTED)
                .podcastId(podcast.getId())
                .build();
    }

    private static RssFeedParser.Item item(String guid) {
        return new RssFeedParser.Item(guid, "Title " + guid, "Desc", Instant.parse("2026-07-10T06:00:00Z"),
                "https://cdn.example.org/" + guid + ".mp3", "audio/mpeg", 60_000, null, null, null);
    }

    @Test
    void syncsChannelAndNewEpisodesAndQueuesAutoDownloads() {
        RssFeedParser.Feed feed = new RssFeedParser.Feed(false, "\"v1\"", "lm",
                new RssFeedParser.Channel("Test Cast", "About", "nl", "Ister FM", "https://example.org/cover.jpg"),
                List.of(item("guid-1"), item("guid-2")));
        when(rssFeedParser.fetch(eq(podcast.getFeedUrl()), any(), any())).thenReturn(Optional.of(feed));
        when(podcastEpisodeRepository.findByPodcastEntityAndGuid(eq(podcast), any())).thenReturn(Optional.empty());
        UUID episode1 = UUID.randomUUID();
        UUID episode2 = UUID.randomUUID();
        when(podcastEpisodeRepository.findEpisodeIdsForPodcastOrdered(podcast.getId(), 2, 0))
                .thenReturn(List.of(episode1, episode2));
        when(mediaFileRepository.existsByPodcastEpisodeEntityId(episode1)).thenReturn(false);
        when(mediaFileRepository.existsByPodcastEpisodeEntityId(episode2)).thenReturn(true);

        subject.handle(event());

        assertEquals("Test Cast", podcast.getTitle());
        assertEquals("Ister FM", podcast.getAuthor());
        assertEquals("nld", podcast.getLanguage());
        assertEquals("\"v1\"", podcast.getFeedEtag());
        assertNotNull(podcast.getLastRefreshedAt());

        ArgumentCaptor<PodcastEpisodeEntity> saved = ArgumentCaptor.forClass(PodcastEpisodeEntity.class);
        verify(podcastEpisodeRepository, times(2)).save(saved.capture());
        assertEquals(List.of("guid-1", "guid-2"),
                saved.getAllValues().stream().map(PodcastEpisodeEntity::getGuid).toList());
        verify(serverEventService, times(2)).createPodcastEpisodeFoundEvent(any());

        // Only the not-yet-downloaded episode gets a download request, on this node's cache dir.
        ArgumentCaptor<PodcastEpisodeDownloadRequestedData> download =
                ArgumentCaptor.forClass(PodcastEpisodeDownloadRequestedData.class);
        verify(messageSender).sendPodcastEpisodeDownloadRequested(download.capture(), eq("node1-cache-directory"));
        assertEquals(episode1, download.getValue().getPodcastEpisodeId());
    }

    @Test
    void existingGuidsAreNotDuplicated() {
        RssFeedParser.Feed feed = new RssFeedParser.Feed(false, null, null,
                new RssFeedParser.Channel("Test Cast", null, null, null, null),
                List.of(item("guid-known")));
        when(rssFeedParser.fetch(any(), any(), any())).thenReturn(Optional.of(feed));
        when(podcastEpisodeRepository.findByPodcastEntityAndGuid(podcast, "guid-known"))
                .thenReturn(Optional.of(PodcastEpisodeEntity.builder().podcastEntity(podcast).guid("guid-known")
                        .enclosureUrl("x").build()));
        when(podcastEpisodeRepository.findEpisodeIdsForPodcastOrdered(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

        subject.handle(event());

        verify(podcastEpisodeRepository, never()).save(any());
        verify(serverEventService, never()).createPodcastEpisodeFoundEvent(any());
    }

    @Test
    void notModifiedOnlyBumpsLastRefreshedAt() {
        when(rssFeedParser.fetch(any(), any(), any())).thenReturn(Optional.of(RssFeedParser.Feed.notModifiedResult()));

        subject.handle(event());

        assertNotNull(podcast.getLastRefreshedAt());
        verify(podcastRepository).save(podcast);
        verify(podcastEpisodeRepository, never()).save(any());
        verify(metadataRepository, never()).save(any());
    }

    @Test
    void inactivePodcastIsSkipped() {
        podcast.setActive(false);

        subject.handle(event());

        verify(rssFeedParser, never()).fetch(any(), any(), any());
    }

    @Test
    void fetchFailureLeavesPodcastUntouched() {
        when(rssFeedParser.fetch(any(), any(), any())).thenReturn(Optional.empty());

        subject.handle(event());

        assertNull(podcast.getLastRefreshedAt());
        verify(podcastRepository, never()).save(any());
    }
}
