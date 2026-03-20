package app.ister.api.controller;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.ShowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void showByIdReturnsFromRepository() {
        UUID id = UUID.randomUUID();
        ShowEntity show = ShowEntity.builder().name("Test").releaseYear(2020).build();
        when(showRepository.findById(id)).thenReturn(Optional.of(show));

        Optional<ShowEntity> result = subject.showById(id);

        assertTrue(result.isPresent());
        assertEquals(show, result.get());
    }

    @Test
    void showByIdReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(showRepository.findById(id)).thenReturn(Optional.empty());

        Optional<ShowEntity> result = subject.showById(id);

        assertTrue(result.isEmpty());
    }

    @Test
    void showsUsesDefaultsWhenNoArgumentsGiven() {
        Page<ShowEntity> page = new PageImpl<>(List.of());
        when(showRepository.findAll(any(Pageable.class))).thenReturn(page);

        Page<ShowEntity> result = subject.shows(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        assertNotNull(result);
        verify(showRepository).findAll(any(Pageable.class));
    }

    @Test
    void showsAppliesPageAndSizeArguments() {
        ShowEntity show = ShowEntity.builder().name("Test").releaseYear(2020).build();
        Page<ShowEntity> page = new PageImpl<>(List.of(show));
        when(showRepository.findAll(any(Pageable.class))).thenReturn(page);

        Page<ShowEntity> result = subject.shows(Optional.of(2), Optional.of(5), Optional.empty(), Optional.empty());

        assertEquals(1, result.getContent().size());
    }

    @Test
    void showsAppliesAscendingSortingOrder() {
        Page<ShowEntity> page = new PageImpl<>(List.of());
        when(showRepository.findAll(any(Pageable.class))).thenReturn(page);

        subject.shows(Optional.empty(), Optional.empty(), Optional.of(SortingEnum.NAME), Optional.of(SortingOrder.ASCENDING));

        verify(showRepository).findAll(any(Pageable.class));
    }

    @Test
    void showsAppliesDescendingSortingOrder() {
        Page<ShowEntity> page = new PageImpl<>(List.of());
        when(showRepository.findAll(any(Pageable.class))).thenReturn(page);

        subject.shows(Optional.empty(), Optional.empty(), Optional.of(SortingEnum.NAME), Optional.of(SortingOrder.DESCENDING));

        verify(showRepository).findAll(any(Pageable.class));
    }

    @Test
    void episodesReturnsEpisodesForShow() {
        UUID showId = UUID.randomUUID();
        ShowEntity show = ShowEntity.builder().name("Test").releaseYear(2020).build();
        show.setId(showId);
        EpisodeEntity episode = EpisodeEntity.builder().number(1).build();
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class))).thenReturn(List.of(episode));

        List<EpisodeEntity> result = subject.episodes(show);

        assertEquals(1, result.size());
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
        when(imageRepository.findByShowEntityId(showId)).thenReturn(List.of(image));

        List<ImageEntity> result = subject.images(show);

        assertEquals(1, result.size());
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
