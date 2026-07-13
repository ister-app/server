package app.ister.api.controller;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.UserPodcastPreferenceEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.SortingOrder;
import app.ister.core.eventdata.PodcastEpisodeDownloadRequestedData;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.UserPodcastPreferenceRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.PodcastPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PodcastEpisodeControllerTest {

    @InjectMocks
    private PodcastEpisodeController subject;

    @Mock
    private PodcastEpisodeRepository podcastEpisodeRepository;

    @Mock
    private WatchStatusRepository watchStatusRepository;

    @Mock
    private UserPodcastPreferenceRepository userPodcastPreferenceRepository;

    @Mock
    private PodcastPreferenceService podcastPreferenceService;

    @Mock
    private MessageSender messageSender;

    @Mock
    private Authentication authentication;

    private final UUID podcastId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(subject, "nodeName", "node-1");
        lenient().when(authentication.getName()).thenReturn("user-1");
    }

    private PodcastEntity podcast() {
        PodcastEntity podcast = PodcastEntity.builder().title("Serial")
                .feedUrl("https://example.org/feed").build();
        podcast.setId(podcastId);
        return podcast;
    }

    private PodcastEpisodeEntity episode(String guid) {
        PodcastEpisodeEntity episode = PodcastEpisodeEntity.builder()
                .podcastEntity(podcast()).guid(guid).enclosureUrl("https://example.org/" + guid).build();
        episode.setId(UUID.randomUUID());
        return episode;
    }

    @Test
    void podcastEpisodeByIdDelegatesToRepository() {
        PodcastEpisodeEntity episode = episode("ep-1");
        when(podcastEpisodeRepository.findById(episode.getId())).thenReturn(Optional.of(episode));

        Optional<PodcastEpisodeEntity> result = subject.podcastEpisodeById(episode.getId());

        assertTrue(result.isPresent());
    }

    @Test
    void podcastEpisodesUsesTheStoredPreferenceWhenNoOrderIsGiven() {
        when(podcastPreferenceService.getEpisodeOrder(authentication, podcastId)).thenReturn(SortingOrder.ASCENDING);
        when(podcastEpisodeRepository.findByPodcastEntityId(eq(podcastId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(episode("ep-1"))));

        Page<PodcastEpisodeEntity> result = subject.podcastEpisodes(podcastId, Optional.empty(),
                Optional.empty(), Optional.empty(), authentication);

        assertEquals(1, result.getTotalElements());
        Pageable pageable = capturedPageable();
        assertEquals(Sort.by("publishedAt").ascending(), pageable.getSort());
        assertEquals(25, pageable.getPageSize());
        assertEquals(0, pageable.getPageNumber());
    }

    @Test
    void podcastEpisodesLetsAnExplicitOrderOverrideThePreference() {
        when(podcastEpisodeRepository.findByPodcastEntityId(eq(podcastId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(episode("ep-1"))));

        subject.podcastEpisodes(podcastId, Optional.of(3), Optional.of(10),
                Optional.of(SortingOrder.DESCENDING), authentication);

        Pageable pageable = capturedPageable();
        assertEquals(Sort.by("publishedAt").descending(), pageable.getSort());
        assertEquals(10, pageable.getPageSize());
        assertEquals(3, pageable.getPageNumber());
        verifyNoInteractions(podcastPreferenceService);
    }

    /** A client asking for a silly page/size must not be able to drag the whole table out. */
    @Test
    void podcastEpisodesClampsPageAndSize() {
        when(podcastEpisodeRepository.findByPodcastEntityId(eq(podcastId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        subject.podcastEpisodes(podcastId, Optional.of(-5), Optional.of(10_000),
                Optional.of(SortingOrder.DESCENDING), authentication);

        Pageable pageable = capturedPageable();
        assertEquals(Paging.MAX_PAGE_SIZE, pageable.getPageSize());
        assertEquals(0, pageable.getPageNumber());
    }

    @Test
    void setPodcastEpisodeOrderDelegatesToService() {
        assertTrue(subject.setPodcastEpisodeOrder(podcastId, SortingOrder.ASCENDING, authentication));

        verify(podcastPreferenceService).setEpisodeOrder(authentication, podcastId, SortingOrder.ASCENDING);
    }

    @Test
    void episodeOrderFallsBackToTheDefaultForPodcastsWithoutAPreference() {
        PodcastEntity withPreference = podcast();
        PodcastEntity withoutPreference = PodcastEntity.builder().title("Radiolab")
                .feedUrl("https://example.org/other").build();
        withoutPreference.setId(UUID.randomUUID());
        when(userPodcastPreferenceRepository.findByUserEntityExternalIdAndPodcastEntityIn("user-1",
                List.of(withPreference, withoutPreference)))
                .thenReturn(List.of(UserPodcastPreferenceEntity.builder()
                        .podcastEntity(withPreference).episodeOrder(SortingOrder.ASCENDING).build()));

        Map<PodcastEntity, SortingOrder> result =
                subject.episodeOrder(List.of(withPreference, withoutPreference), authentication);

        assertEquals(SortingOrder.ASCENDING, result.get(withPreference));
        assertEquals(PodcastPreferenceService.DEFAULT_EPISODE_ORDER, result.get(withoutPreference));
    }

    @Test
    void downloadPodcastEpisodeSendsTheEventToThisNodesCacheDirectory() {
        UUID episodeId = UUID.randomUUID();
        when(podcastEpisodeRepository.existsById(episodeId)).thenReturn(true);

        assertTrue(subject.downloadPodcastEpisode(episodeId));

        ArgumentCaptor<PodcastEpisodeDownloadRequestedData> data =
                ArgumentCaptor.forClass(PodcastEpisodeDownloadRequestedData.class);
        verify(messageSender).sendPodcastEpisodeDownloadRequested(data.capture(), eq("node-1-cache-directory"));
        assertEquals(episodeId, data.getValue().getPodcastEpisodeId());
    }

    @Test
    void downloadPodcastEpisodeFailsForAnUnknownEpisode() {
        UUID episodeId = UUID.randomUUID();
        when(podcastEpisodeRepository.existsById(episodeId)).thenReturn(false);

        assertThrows(NoSuchElementException.class, () -> subject.downloadPodcastEpisode(episodeId));

        verifyNoInteractions(messageSender);
    }

    @Test
    void podcastMetadataAndMediaFileAreReadFromTheEpisode() {
        PodcastEpisodeEntity episode = episode("ep-1");
        MetadataEntity metadata = MetadataEntity.builder().title("Episode 1").build();
        MediaFileEntity mediaFile = MediaFileEntity.builder().build();
        episode.setMetadataEntities(List.of(metadata));
        episode.setMediaFileEntities(List.of(mediaFile));

        assertEquals(podcastId, subject.podcast(episode).getId());
        assertEquals(List.of(metadata), subject.metadata(episode));
        assertEquals(List.of(mediaFile), subject.mediaFile(episode));
        assertTrue(subject.downloaded(episode));
    }

    @Test
    void downloadedIsFalseWithoutMediaFiles() {
        PodcastEpisodeEntity episode = episode("ep-1");

        assertFalse(subject.downloaded(episode));

        episode.setMediaFileEntities(List.of());
        assertFalse(subject.downloaded(episode));
    }

    @Test
    void durationPrefersTheMeasuredDurationOfTheDownloadedFile() {
        PodcastEpisodeEntity episode = episode("ep-1");
        episode.setDurationHintInMilliseconds(60_000);
        episode.setMediaFileEntities(List.of(
                MediaFileEntity.builder().durationInMilliseconds(0).build(),
                MediaFileEntity.builder().durationInMilliseconds(120_000).build()));

        assertEquals(120_000L, subject.durationInMilliseconds(episode));
    }

    @Test
    void durationFallsBackToTheFeedHintUntilTheFileIsMeasured() {
        PodcastEpisodeEntity episode = episode("ep-1");
        episode.setDurationHintInMilliseconds(60_000);

        assertEquals(60_000L, subject.durationInMilliseconds(episode));
    }

    @Test
    void durationIsNullWhenNeitherTheFileNorTheFeedKnowsIt() {
        PodcastEpisodeEntity episode = episode("ep-1");
        episode.setMediaFileEntities(List.of(MediaFileEntity.builder().durationInMilliseconds(0).build()));

        assertNull(subject.durationInMilliseconds(episode));
    }

    @Test
    void watchStatusBatchMapsEpisodesWithoutStatusToAnEmptyList() {
        PodcastEpisodeEntity started = episode("ep-1");
        PodcastEpisodeEntity untouched = episode("ep-2");
        WatchStatusEntity status = WatchStatusEntity.builder().podcastEpisodeEntity(started).build();
        when(watchStatusRepository.findByUserEntityExternalIdAndPodcastEpisodeEntityIn(eq("user-1"),
                eq(List.of(started, untouched)), any(Sort.class))).thenReturn(List.of(status));

        Map<PodcastEpisodeEntity, List<WatchStatusEntity>> result =
                subject.watchStatus(List.of(started, untouched), authentication);

        assertEquals(List.of(status), result.get(started));
        assertEquals(List.of(), result.get(untouched));
    }

    private Pageable capturedPageable() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(podcastEpisodeRepository).findByPodcastEntityId(eq(podcastId), pageable.capture());
        return pageable.getValue();
    }
}
