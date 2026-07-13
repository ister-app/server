package app.ister.api.controller;

import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.UserPodcastPreferenceRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.PodcastPreferenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Schema-wiring test for the per-user podcast episode order: the query sorts by the caller's
 * stored preference unless it is given an explicit direction, and the mutation reaches the service.
 */
@GraphQlTest(PodcastEpisodeController.class)
class PodcastEpisodeControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private PodcastEpisodeRepository podcastEpisodeRepository;

    @MockitoBean
    private WatchStatusRepository watchStatusRepository;

    @MockitoBean
    private UserPodcastPreferenceRepository userPodcastPreferenceRepository;

    @MockitoBean
    private PodcastPreferenceService podcastPreferenceService;

    @MockitoBean
    private MessageSender messageSender;

    private final UUID podcastId = UUID.randomUUID();

    /** The resolvers take an Authentication argument; without one they fail before any logic runs. */
    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user-1", "n/a", "ROLE_user"));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private void mockEpisodePage() {
        PodcastEntity podcast = PodcastEntity.builder().title("Serial").feedUrl("https://example.org/feed").build();
        podcast.setId(podcastId);
        PodcastEpisodeEntity episode = PodcastEpisodeEntity.builder().podcastEntity(podcast).guid("ep-1").build();
        episode.setId(UUID.randomUUID());
        when(podcastEpisodeRepository.findByPodcastEntityId(eq(podcastId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(episode)));
    }

    /** The whole point of storing the order server-side: a client that asks for nothing gets it. */
    @Test
    void podcastEpisodesSortsByTheStoredPreferenceWhenNoOrderIsGiven() {
        mockEpisodePage();
        when(podcastPreferenceService.getEpisodeOrder(any(), eq(podcastId))).thenReturn(SortingOrder.ASCENDING);

        graphQlTester.document("""
                        query($podcastId: ID!) {
                          podcastEpisodes(podcastId: $podcastId) { content { id } totalPages }
                        }""")
                .variable("podcastId", podcastId)
                .execute()
                .path("podcastEpisodes.content").entityList(Object.class).hasSize(1);

        assertEquals(Sort.by("publishedAt").ascending(), capturedSort());
    }

    @Test
    void podcastEpisodesLetsAnExplicitOrderOverrideThePreference() {
        mockEpisodePage();

        graphQlTester.document("""
                        query($podcastId: ID!) {
                          podcastEpisodes(podcastId: $podcastId, sortingOrder: DESCENDING) { content { id } }
                        }""")
                .variable("podcastId", podcastId)
                .execute()
                .path("podcastEpisodes.content").entityList(Object.class).hasSize(1);

        assertEquals(Sort.by("publishedAt").descending(), capturedSort());
        verify(podcastPreferenceService, org.mockito.Mockito.never()).getEpisodeOrder(any(), any());
    }

    @Test
    void setPodcastEpisodeOrderReachesTheService() {
        graphQlTester.document("""
                        mutation($podcastId: ID!) {
                          setPodcastEpisodeOrder(podcastId: $podcastId, order: ASCENDING)
                        }""")
                .variable("podcastId", podcastId)
                .execute()
                .path("setPodcastEpisodeOrder").entity(Boolean.class).isEqualTo(true);

        verify(podcastPreferenceService).setEpisodeOrder(any(), eq(podcastId), eq(SortingOrder.ASCENDING));
    }

    private Sort capturedSort() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(podcastEpisodeRepository).findByPodcastEntityId(eq(podcastId), pageable.capture());
        return pageable.getValue().getSort();
    }
}
