package app.ister.api.controller;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.service.LibraryAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShowControllerTest {

    @InjectMocks
    private ShowController subject;

    @Mock
    private ShowRepository showRepository;

    @Mock
    private EpisodeRepository episodeRepository;

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private LibraryAccessService libraryAccessService;

    @Mock
    private Authentication authentication;

    private LibraryEntity library() {
        LibraryEntity library = LibraryEntity.builder().name("Shows").build();
        library.setId(UUID.randomUUID());
        return library;
    }

    @Test
    void showByIdReturnsFromRepository() {
        UUID id = UUID.randomUUID();
        ShowEntity show = ShowEntity.builder().name("Test").releaseYear(2020).libraryEntity(library()).build();
        when(showRepository.findById(id)).thenReturn(Optional.of(show));
        when(libraryAccessService.canAccess(any(LibraryEntity.class), any())).thenReturn(true);

        Optional<ShowEntity> result = subject.showById(id, authentication);

        assertTrue(result.isPresent());
        assertEquals(show, result.get());
    }

    @Test
    void showByIdReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(showRepository.findById(id)).thenReturn(Optional.empty());

        Optional<ShowEntity> result = subject.showById(id, authentication);

        assertTrue(result.isEmpty());
    }

    @Test
    void showByIdOfAnInaccessibleShowReturnsEmpty() {
        UUID id = UUID.randomUUID();
        ShowEntity show = ShowEntity.builder().name("Test").releaseYear(2020).libraryEntity(library()).build();
        when(showRepository.findById(id)).thenReturn(Optional.of(show));
        when(libraryAccessService.canAccess(any(LibraryEntity.class), any())).thenReturn(false);

        Optional<ShowEntity> result = subject.showById(id, authentication);

        assertTrue(result.isEmpty());
    }

    @Test
    void showsUsesDefaultsWhenNoArgumentsGiven() {
        Page<ShowEntity> page = new PageImpl<>(List.of());
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.empty());
        when(showRepository.findAll(any(Pageable.class))).thenReturn(page);

        Page<ShowEntity> result = subject.shows(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), authentication);

        assertNotNull(result);
        verify(showRepository).findAll(any(Pageable.class));
    }

    @Test
    void showsAppliesPageAndSizeArguments() {
        ShowEntity show = ShowEntity.builder().name("Test").releaseYear(2020).build();
        Page<ShowEntity> page = new PageImpl<>(List.of(show));
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.empty());
        when(showRepository.findAll(any(Pageable.class))).thenReturn(page);

        Page<ShowEntity> result = subject.shows(Optional.of(2), Optional.of(5), Optional.empty(), Optional.empty(), Optional.empty(), authentication);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void showsAppliesAscendingSortingOrder() {
        Page<ShowEntity> page = new PageImpl<>(List.of());
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.empty());
        when(showRepository.findAll(any(Pageable.class))).thenReturn(page);

        subject.shows(Optional.empty(), Optional.empty(), Optional.of(SortingEnum.NAME), Optional.of(SortingOrder.ASCENDING), Optional.empty(), authentication);

        verify(showRepository).findAll(any(Pageable.class));
    }

    @Test
    void showsAppliesDescendingSortingOrder() {
        Page<ShowEntity> page = new PageImpl<>(List.of());
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.empty());
        when(showRepository.findAll(any(Pageable.class))).thenReturn(page);

        subject.shows(Optional.empty(), Optional.empty(), Optional.of(SortingEnum.NAME), Optional.of(SortingOrder.DESCENDING), Optional.empty(), authentication);

        verify(showRepository).findAll(any(Pageable.class));
    }

    @Test
    void showsWithRestrictedAccessQueriesOnlyTheAllowedLibraries() {
        UUID allowedLibraryId = UUID.randomUUID();
        Page<ShowEntity> page = new PageImpl<>(List.of());
        when(libraryAccessService.allowedLibraryIds(any())).thenReturn(Optional.of(Set.of(allowedLibraryId)));
        when(showRepository.findByLibraryEntityIdIn(eq(Set.of(allowedLibraryId)), any(Pageable.class))).thenReturn(page);

        Page<ShowEntity> result = subject.shows(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), authentication);

        assertNotNull(result);
        verify(showRepository).findByLibraryEntityIdIn(eq(Set.of(allowedLibraryId)), any(Pageable.class));
        verify(showRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void showsFiltersWithLibraryIdWhenPresent() {
        UUID libraryId = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().name("Shows").build();
        library.setId(libraryId);
        Page<ShowEntity> page = new PageImpl<>(List.of());
        when(libraryAccessService.canAccess(any(UUID.class), any())).thenReturn(true);
        when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
        when(showRepository.findByLibraryEntity(eq(library), any(Pageable.class))).thenReturn(page);

        Page<ShowEntity> result = subject.shows(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(libraryId), authentication);

        assertNotNull(result);
        verify(showRepository).findByLibraryEntity(eq(library), any(Pageable.class));
        verify(showRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void showsWithAForbiddenLibraryIdReturnsAnEmptyPage() {
        UUID libraryId = UUID.randomUUID();
        when(libraryAccessService.canAccess(any(UUID.class), any())).thenReturn(false);

        Page<ShowEntity> result = subject.shows(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(libraryId), authentication);

        assertEquals(0, result.getTotalElements());
        verify(showRepository, never()).findByLibraryEntity(any(), any(Pageable.class));
        verify(showRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void showsReturnsEmptyWhenLibraryIdNotFound() {
        UUID libraryId = UUID.randomUUID();
        when(libraryAccessService.canAccess(any(UUID.class), any())).thenReturn(true);
        when(libraryRepository.findById(libraryId)).thenReturn(Optional.empty());

        Page<ShowEntity> result = subject.shows(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(libraryId), authentication);

        assertEquals(0, result.getTotalElements());
        verify(showRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void episodesReturnsEpisodesForShow() {
        UUID showId = UUID.randomUUID();
        ShowEntity show = ShowEntity.builder().name("Test").releaseYear(2020).build();
        show.setId(showId);
        EpisodeEntity episode = EpisodeEntity.builder().number(1).showEntity(show).build();
        when(episodeRepository.findByShowEntityIdIn(eq(List.of(showId)), any(Sort.class))).thenReturn(List.of(episode));

        Map<ShowEntity, List<EpisodeEntity>> result = subject.episodes(List.of(show));

        assertEquals(1, result.get(show).size());
    }

    @Test
    void seasonsReturnsShowSeasons() {
        SeasonEntity season = SeasonEntity.builder().number(1).build();
        ShowEntity show = ShowEntity.builder().name("Test").releaseYear(2020).seasonEntities(List.of(season)).build();

        List<SeasonEntity> result = subject.seasons(show);

        assertEquals(1, result.size());
    }

    @Test
    void metadataReturnsShowMetadata() {
        MetadataEntity meta = MetadataEntity.builder().build();
        ShowEntity show = ShowEntity.builder().name("Test").releaseYear(2020).metadataEntities(List.of(meta)).build();

        List<MetadataEntity> result = subject.metadata(show);

        assertEquals(1, result.size());
    }

    @Test
    void imagesReturnsFromRepository() {
        UUID showId = UUID.randomUUID();
        ShowEntity show = ShowEntity.builder().name("Test").releaseYear(2020).build();
        show.setId(showId);
        ImageEntity image = ImageEntity.builder().build();
        image.setShowEntity(show);
        when(imageRepository.findByShowEntityIdIn(List.of(showId))).thenReturn(List.of(image));

        Map<ShowEntity, List<ImageEntity>> result = subject.images(List.of(show));

        assertEquals(1, result.get(show).size());
    }

    @Test
    void showForImageReturnsShowFromImage() {
        ShowEntity show = ShowEntity.builder().name("Test").releaseYear(2020).build();
        ImageEntity image = ImageEntity.builder().build();
        image.setShowEntity(show);

        ShowEntity result = subject.showForImage(image);

        assertEquals(show, result);
    }
}
