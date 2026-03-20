package app.ister.api.controller;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
    private UserService userService;

    @Mock
    private Authentication authentication;

    @Test
    void episodeByIdReturnsFromRepository() {
        UUID id = UUID.randomUUID();
        EpisodeEntity episode = EpisodeEntity.builder().number(1).build();
        when(episodeRepository.findById(id)).thenReturn(Optional.of(episode));

        Optional<EpisodeEntity> result = subject.episodeById(id);

        assertTrue(result.isPresent());
        assertEquals(episode, result.get());
    }

    @Test
    void episodeByIdReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(episodeRepository.findById(id)).thenReturn(Optional.empty());

        Optional<EpisodeEntity> result = subject.episodeById(id);

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
        WatchStatusEntity ws = WatchStatusEntity.builder().watched(false).build();
        when(authentication.getName()).thenReturn("user1");
        when(watchStatusRepository.findByUserEntityExternalIdAndEpisodeEntity(eq("user1"), eq(episode), any(Sort.class)))
                .thenReturn(List.of(ws));

        List<WatchStatusEntity> result = subject.watchStatus(episode, authentication);

        assertEquals(1, result.size());
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

    @Test
    void episodesRecentWatchedReturnsCurrentEpisodeWhenNotWatched() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity unwatched = WatchStatusEntity.builder().watched(false).build();
        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(unwatched))).build();
        ep1.setId(ep1Id);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1));

        List<EpisodeEntity> result = subject.episodesRecentWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(ep1, result.get(0));
    }

    @Test
    void episodesRecentWatchedReturnsNextEpisodeWhenCurrentIsWatched() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID ep2Id = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity watched = WatchStatusEntity.builder().watched(true).build();
        watched.setDateUpdated(Instant.now());

        WatchStatusEntity unwatched = WatchStatusEntity.builder().watched(false).build();

        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(watched))).build();
        ep1.setId(ep1Id);
        EpisodeEntity ep2 = EpisodeEntity.builder().number(2).watchStatusEntities(new ArrayList<>(List.of(unwatched))).build();
        ep2.setId(ep2Id);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1, ep2));

        List<EpisodeEntity> result = subject.episodesRecentWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(ep2, result.get(0));
    }

    @Test
    void episodesRecentWatchedReturnsOldWatchedEpisode() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID ep2Id = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        // ep1 is watched recently
        WatchStatusEntity watched = WatchStatusEntity.builder().watched(true).build();
        watched.setDateUpdated(Instant.now());

        // ep2 was watched more than 300 days ago (should be treated as unwatched)
        WatchStatusEntity oldWatched = WatchStatusEntity.builder().watched(true).build();
        oldWatched.setDateUpdated(Instant.now().minus(301, ChronoUnit.DAYS));

        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(watched))).build();
        ep1.setId(ep1Id);
        EpisodeEntity ep2 = EpisodeEntity.builder().number(2).watchStatusEntities(new ArrayList<>(List.of(oldWatched))).build();
        ep2.setId(ep2Id);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1, ep2));

        List<EpisodeEntity> result = subject.episodesRecentWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(ep2, result.get(0));
    }

    @Test
    void episodesRecentWatchedReturnsEmptyWhenAllEpisodesWatched() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity watched = WatchStatusEntity.builder().watched(true).build();
        watched.setDateUpdated(Instant.now());

        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(watched))).build();
        ep1.setId(ep1Id);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1));

        List<EpisodeEntity> result = subject.episodesRecentWatched(authentication);

        assertTrue(result.isEmpty());
    }

    @Test
    void episodesRecentWatchedReturnsEpisodeWithNoWatchStatus() {
        UUID showId = UUID.randomUUID();
        UUID ep1Id = UUID.randomUUID();
        UUID ep2Id = UUID.randomUUID();

        UserEntity user = UserEntity.builder().build();
        user.setId(UUID.randomUUID());

        WatchStatusEntity watched = WatchStatusEntity.builder().watched(true).build();
        watched.setDateUpdated(Instant.now());

        EpisodeEntity ep1 = EpisodeEntity.builder().number(1).watchStatusEntities(new ArrayList<>(List.of(watched))).build();
        ep1.setId(ep1Id);
        // ep2 has no watch status at all
        EpisodeEntity ep2 = EpisodeEntity.builder().number(2).watchStatusEntities(new ArrayList<>()).build();
        ep2.setId(ep2Id);

        when(authentication.getName()).thenReturn("user1");
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(user.getId()))
                .thenReturn(List.<String[]>of(new String[]{ep1Id.toString(), showId.toString()}));
        when(episodeRepository.findByShowEntityId(eq(showId), any(Sort.class)))
                .thenReturn(List.of(ep1, ep2));

        List<EpisodeEntity> result = subject.episodesRecentWatched(authentication);

        assertEquals(1, result.size());
        assertEquals(ep2, result.get(0));
    }
}
