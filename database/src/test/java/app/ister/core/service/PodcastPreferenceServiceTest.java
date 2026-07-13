package app.ister.core.service;

import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserPodcastPreferenceEntity;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.repository.UserPodcastPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PodcastPreferenceServiceTest {

    @InjectMocks
    private PodcastPreferenceService subject;

    @Mock
    private UserService userService;

    @Mock
    private UserPodcastPreferenceRepository userPodcastPreferenceRepository;

    @Mock
    private PodcastRepository podcastRepository;

    @Mock
    private Authentication authentication;

    private UserEntity user;
    private PodcastEntity podcast;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(UUID.randomUUID()).externalId("user-1").build();
        podcast = PodcastEntity.builder().title("Serial").feedUrl("https://example.org/feed").build();
        podcast.setId(UUID.randomUUID());
    }

    private void mockUserAndPodcast() {
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(podcastRepository.findById(podcast.getId())).thenReturn(Optional.of(podcast));
    }

    @Test
    void episodeOrderDefaultsToNewestFirstWhenTheUserNeverSetOne() {
        mockUserAndPodcast();
        when(userPodcastPreferenceRepository.findByUserEntityAndPodcastEntity(user, podcast))
                .thenReturn(Optional.empty());

        assertEquals(SortingOrder.DESCENDING, subject.getEpisodeOrder(authentication, podcast.getId()));
    }

    @Test
    void episodeOrderReturnsTheStoredPreference() {
        mockUserAndPodcast();
        when(userPodcastPreferenceRepository.findByUserEntityAndPodcastEntity(user, podcast))
                .thenReturn(Optional.of(UserPodcastPreferenceEntity.builder()
                        .userEntity(user).podcastEntity(podcast)
                        .episodeOrder(SortingOrder.ASCENDING).build()));

        assertEquals(SortingOrder.ASCENDING, subject.getEpisodeOrder(authentication, podcast.getId()));
    }

    @Test
    void setEpisodeOrderInsertsARowWhenThereIsNone() {
        mockUserAndPodcast();
        when(userPodcastPreferenceRepository.findByUserEntityAndPodcastEntity(user, podcast))
                .thenReturn(Optional.empty());

        subject.setEpisodeOrder(authentication, podcast.getId(), SortingOrder.ASCENDING);

        ArgumentCaptor<UserPodcastPreferenceEntity> saved =
                ArgumentCaptor.forClass(UserPodcastPreferenceEntity.class);
        verify(userPodcastPreferenceRepository).save(saved.capture());
        assertEquals(user, saved.getValue().getUserEntity());
        assertEquals(podcast, saved.getValue().getPodcastEntity());
        assertEquals(SortingOrder.ASCENDING, saved.getValue().getEpisodeOrder());
    }

    @Test
    void setEpisodeOrderUpdatesTheExistingRow() {
        mockUserAndPodcast();
        UserPodcastPreferenceEntity existing = UserPodcastPreferenceEntity.builder()
                .userEntity(user).podcastEntity(podcast).episodeOrder(SortingOrder.ASCENDING).build();
        when(userPodcastPreferenceRepository.findByUserEntityAndPodcastEntity(user, podcast))
                .thenReturn(Optional.of(existing));

        subject.setEpisodeOrder(authentication, podcast.getId(), SortingOrder.DESCENDING);

        assertEquals(SortingOrder.DESCENDING, existing.getEpisodeOrder());
        verify(userPodcastPreferenceRepository).save(existing);
    }

    @Test
    void setEpisodeOrderRejectsAnUnknownPodcast() {
        UUID unknownId = UUID.randomUUID();
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(podcastRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> subject.setEpisodeOrder(authentication, unknownId, SortingOrder.ASCENDING));
        verify(userPodcastPreferenceRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
