package app.ister.api.controller;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.LibraryAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EpisodeControllerTest {

    @InjectMocks
    private EpisodeController subject;

    @Mock
    private EpisodeRepository episodeRepository;

    @Mock
    private WatchStatusRepository watchStatusRepository;

    @Mock
    private LibraryAccessService libraryAccessService;

    @Mock
    private Authentication authentication;

    @Test
    void episodeByIdReturnsFromRepository() {
        UUID id = UUID.randomUUID();
        ShowEntity showOfEpisode = ShowEntity.builder().name("Test").releaseYear(2020)
                .libraryEntity(app.ister.core.entity.LibraryEntity.builder().name("Shows").build()).build();
        EpisodeEntity episode = EpisodeEntity.builder().number(1).showEntity(showOfEpisode).build();
        when(episodeRepository.findById(id)).thenReturn(Optional.of(episode));
        when(libraryAccessService.canAccess(any(app.ister.core.entity.LibraryEntity.class), any())).thenReturn(true);

        Optional<EpisodeEntity> result = subject.episodeById(id, authentication);

        assertTrue(result.isPresent());
        assertEquals(episode, result.get());
    }

    @Test
    void episodeByIdReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(episodeRepository.findById(id)).thenReturn(Optional.empty());

        Optional<EpisodeEntity> result = subject.episodeById(id, authentication);

        assertTrue(result.isEmpty());
    }

    @Test
    void showReturnsEpisodesShow() {
        ShowEntity show = ShowEntity.builder().name("Test").releaseYear(2020).build();
        EpisodeEntity episode = EpisodeEntity.builder().number(1).showEntity(show).build();

        ShowEntity result = subject.show(episode);

        assertEquals(show, result);
    }

    @Test
    void seasonReturnsEpisodeSeason() {
        SeasonEntity season = SeasonEntity.builder().number(1).build();
        EpisodeEntity episode = EpisodeEntity.builder().number(1).seasonEntity(season).build();

        SeasonEntity result = subject.season(episode);

        assertEquals(season, result);
    }

    @Test
    void metadataReturnsEpisodeMetadata() {
        MetadataEntity meta = MetadataEntity.builder().build();
        EpisodeEntity episode = EpisodeEntity.builder().number(1).metadataEntities(List.of(meta)).build();

        List<MetadataEntity> result = subject.metadata(episode);

        assertEquals(1, result.size());
    }

    @Test
    void imagesReturnsEpisodeImages() {
        ImageEntity image = ImageEntity.builder().build();
        EpisodeEntity episode = EpisodeEntity.builder().number(1).imagesEntities(List.of(image)).build();

        List<ImageEntity> result = subject.images(episode);

        assertEquals(1, result.size());
    }

    @Test
    void watchStatusReturnsForUserAndEpisode() {
        EpisodeEntity episode = EpisodeEntity.builder().number(1).build();
        org.springframework.test.util.ReflectionTestUtils.setField(episode, "id", UUID.randomUUID());
        WatchStatusEntity ws = WatchStatusEntity.builder().watched(false).episodeEntity(episode).build();
        when(authentication.getName()).thenReturn("user1");
        when(watchStatusRepository.findByUserEntityExternalIdAndEpisodeEntityIn(eq("user1"), eq(List.of(episode)), any(Sort.class)))
                .thenReturn(List.of(ws));

        Map<EpisodeEntity, List<WatchStatusEntity>> result = subject.watchStatus(List.of(episode), authentication);

        assertEquals(1, result.get(episode).size());
    }

    @Test
    void mediaFileReturnsEpisodeMediaFiles() {
        MediaFileEntity mediaFile = MediaFileEntity.builder().build();
        EpisodeEntity episode = EpisodeEntity.builder().number(1).mediaFileEntities(List.of(mediaFile)).build();

        List<MediaFileEntity> result = subject.mediaFile(episode);

        assertEquals(1, result.size());
    }

    @Test
    void mediaFileStreamsReturnsStreamsFromMediaFile() {
        MediaFileStreamEntity stream = MediaFileStreamEntity.builder().build();
        MediaFileEntity mediaFile = MediaFileEntity.builder().mediaFileStreamEntity(List.of(stream)).build();

        List<MediaFileStreamEntity> result = subject.mediaFileStreams(mediaFile);

        assertEquals(1, result.size());
    }

    @Test
    void episodesForMediaFileReturnsEpisodeWhenPresent() {
        EpisodeEntity episode = EpisodeEntity.builder().number(1).build();
        MediaFileEntity mediaFile = MediaFileEntity.builder().build();
        mediaFile.setEpisodeEntity(episode);

        List<EpisodeEntity> result = subject.episodes(mediaFile);

        assertEquals(1, result.size());
        assertEquals(episode, result.get(0));
    }

    @Test
    void episodesForMediaFileReturnsEmptyWhenNoEpisode() {
        MediaFileEntity mediaFile = MediaFileEntity.builder().build();

        List<EpisodeEntity> result = subject.episodes(mediaFile);

        assertTrue(result.isEmpty());
    }
}
