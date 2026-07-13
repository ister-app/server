package app.ister.api.controller;

import app.ister.api.service.ItunesSearchService;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.eventdata.PodcastRefreshRequestedData;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ServerEventService;
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

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PodcastControllerTest {

    @InjectMocks
    private PodcastController subject;

    @Mock
    private PodcastRepository podcastRepository;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private ItunesSearchService itunesSearchService;

    @Mock
    private ServerEventService serverEventService;

    @Mock
    private MessageSender messageSender;

    private PodcastEntity podcast(String title) {
        PodcastEntity podcast = PodcastEntity.builder()
                .title(title).feedUrl("https://example.org/feed").active(true).build();
        podcast.setId(UUID.randomUUID());
        return podcast;
    }

    @Test
    void podcastByIdDelegatesToRepository() {
        PodcastEntity podcast = podcast("Serial");
        when(podcastRepository.findById(podcast.getId())).thenReturn(Optional.of(podcast));

        Optional<PodcastEntity> result = subject.podcastById(podcast.getId());

        assertTrue(result.isPresent());
        assertEquals(podcast, result.get());
    }

    /** Podcasts store their name in a "title" column, so the default NAME sort has to be rewritten. */
    @Test
    void podcastsSortsOnTitleInsteadOfName() {
        when(podcastRepository.findByActiveTrue(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(podcast("Serial"))));

        Page<PodcastEntity> result = subject.podcasts(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals(1, result.getTotalElements());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(podcastRepository).findByActiveTrue(pageable.capture());
        assertEquals(Sort.by("title").ascending(), pageable.getValue().getSort());
        assertEquals(20, pageable.getValue().getPageSize());
    }

    @Test
    void podcastsFiltersOnLibraryWhenGiven() {
        UUID libraryId = UUID.randomUUID();
        when(podcastRepository.findByLibraryEntityIdAndActiveTrue(eq(libraryId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(podcast("Serial"))));

        Page<PodcastEntity> result = subject.podcasts(Optional.of(2), Optional.of(5),
                Optional.of(SortingEnum.NAME), Optional.of(SortingOrder.DESCENDING), Optional.of(libraryId));

        assertEquals(1, result.getTotalElements());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(podcastRepository).findByLibraryEntityIdAndActiveTrue(eq(libraryId), pageable.capture());
        assertEquals(Sort.by("title").descending(), pageable.getValue().getSort());
        assertEquals(2, pageable.getValue().getPageNumber());
        assertEquals(5, pageable.getValue().getPageSize());
        verify(podcastRepository, never()).findByActiveTrue(any(Pageable.class));
    }

    @Test
    void searchPodcastDirectoryDelegatesToItunes() {
        ItunesSearchService.DirectoryResult hit =
                new ItunesSearchService.DirectoryResult("Serial", "Sarah", "https://example.org/feed", null);
        when(itunesSearchService.search("serial", 20)).thenReturn(List.of(hit));

        List<ItunesSearchService.DirectoryResult> result = subject.searchPodcastDirectory("serial");

        assertEquals(List.of(hit), result);
    }

    @Test
    void subscribePodcastCreatesPodcastAndRequestsRefresh() {
        LibraryEntity library = LibraryEntity.builder().name("Podcasts").libraryType(LibraryType.PODCAST).build();
        when(podcastRepository.findByFeedUrl("https://example.org/feed")).thenReturn(Optional.empty());
        when(libraryRepository.findFirstByLibraryType(LibraryType.PODCAST)).thenReturn(Optional.of(library));

        PodcastEntity result = subject.subscribePodcast("  https://example.org/feed  ");

        assertTrue(result.isActive());
        assertEquals("https://example.org/feed", result.getFeedUrl());
        assertEquals(library, result.getLibraryEntity());
        verify(podcastRepository).save(result);
        verify(serverEventService).createPodcastFoundEvent(result.getId());
        verify(messageSender).sendPodcastRefreshRequested(any(PodcastRefreshRequestedData.class));
    }

    /** Re-subscribing reactivates the existing row instead of creating a duplicate feed. */
    @Test
    void subscribePodcastReactivatesExistingPodcast() {
        PodcastEntity existing = podcast("Serial");
        existing.setActive(false);
        when(podcastRepository.findByFeedUrl("https://example.org/feed")).thenReturn(Optional.of(existing));

        PodcastEntity result = subject.subscribePodcast("https://example.org/feed");

        assertEquals(existing, result);
        assertTrue(result.isActive());
        verify(podcastRepository).save(existing);
        verify(messageSender).sendPodcastRefreshRequested(any(PodcastRefreshRequestedData.class));
        verifyNoInteractions(serverEventService, libraryRepository);
    }

    @Test
    void subscribePodcastFailsWithoutPodcastLibrary() {
        when(podcastRepository.findByFeedUrl("https://example.org/feed")).thenReturn(Optional.empty());
        when(libraryRepository.findFirstByLibraryType(LibraryType.PODCAST)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> subject.subscribePodcast("https://example.org/feed"));

        verifyNoInteractions(messageSender);
    }

    @Test
    void unsubscribePodcastDeactivatesAndRemovesFromSearch() {
        PodcastEntity podcast = podcast("Serial");
        when(podcastRepository.findById(podcast.getId())).thenReturn(Optional.of(podcast));

        assertTrue(subject.unsubscribePodcast(podcast.getId()));

        assertFalse(podcast.isActive());
        verify(podcastRepository).save(podcast);
        verify(serverEventService).createSearchDeleteEvent(SearchEntityType.PODCAST, podcast.getId());
    }

    @Test
    void unsubscribePodcastFailsWhenPodcastIsUnknown() {
        UUID id = UUID.randomUUID();
        when(podcastRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> subject.unsubscribePodcast(id));

        verifyNoInteractions(serverEventService);
    }

    @Test
    void refreshPodcastsRequestsARefreshForEveryActivePodcast() {
        PodcastEntity first = podcast("Serial");
        PodcastEntity second = podcast("Radiolab");
        when(podcastRepository.findByActiveTrue()).thenReturn(List.of(first, second));

        assertTrue(subject.refreshPodcasts());

        ArgumentCaptor<PodcastRefreshRequestedData> data =
                ArgumentCaptor.forClass(PodcastRefreshRequestedData.class);
        verify(messageSender, times(2)).sendPodcastRefreshRequested(data.capture());
        assertEquals(List.of(first.getId(), second.getId()),
                data.getAllValues().stream().map(PodcastRefreshRequestedData::getPodcastId).toList());
    }

    @Test
    void metadataReturnsThePodcastsMetadata() {
        MetadataEntity metadata = MetadataEntity.builder().title("Serial").build();
        PodcastEntity podcast = podcast("Serial");
        podcast.setMetadataEntities(List.of(metadata));

        assertEquals(List.of(metadata), subject.metadata(podcast));
    }

    @Test
    void imagesBatchMapsPodcastsWithoutImagesToAnEmptyList() {
        PodcastEntity withImage = podcast("Serial");
        PodcastEntity withoutImage = podcast("Radiolab");
        ImageEntity image = ImageEntity.builder().build();
        image.setPodcastEntityId(withImage.getId());
        when(imageRepository.findByPodcastEntityIdIn(List.of(withImage.getId(), withoutImage.getId())))
                .thenReturn(List.of(image));

        Map<PodcastEntity, List<ImageEntity>> result = subject.images(List.of(withImage, withoutImage));

        assertEquals(List.of(image), result.get(withImage));
        assertEquals(List.of(), result.get(withoutImage));
    }
}
