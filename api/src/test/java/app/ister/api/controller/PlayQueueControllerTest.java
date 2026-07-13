package app.ister.api.controller;

import app.ister.api.dto.CreatePlayQueueInput;
import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PlayQueueEntity;
import app.ister.core.entity.PlayQueueItemEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.PodcastEpisodeEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.MediaType;
import app.ister.core.enums.PlayQueueSourceType;
import app.ister.api.dto.StreamSettingsInput;
import app.ister.core.enums.PlayState;
import app.ister.core.enums.SubtitleFormat;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PodcastEpisodeRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.PlayQueuePrefetchService;
import app.ister.core.service.PlayQueueService;
import app.ister.core.status.PlaybackStatusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlayQueueControllerTest {

    @InjectMocks
    private PlayQueueController subject;

    @Mock
    private PlayQueueService playQueueService;

    @Mock
    private EpisodeRepository episodeRepository;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private ChapterRepository chapterRepository;

    @Mock
    private PodcastEpisodeRepository podcastEpisodeRepository;

    @Mock
    private MediaFileRepository mediaFileRepository;

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private PlayQueuePrefetchService playQueuePrefetchService;

    @Mock
    private PlaybackStatusService playbackStatusService;

    @Mock
    private app.ister.core.status.PlaybackSessionRegistry playbackSessionRegistry;

    @Mock
    private app.ister.core.status.PlaybackCommandService playbackCommandService;

    @Mock
    private Authentication authentication;

    private PlayQueueEntity buildQueueWithUser() {
        UserEntity user = UserEntity.builder().name("test-user").externalId("sub-123").build();
        user.setId(UUID.randomUUID());
        return PlayQueueEntity.builder().userEntity(user).build();
    }

    private PlayQueueItemEntity buildItem(UUID id) {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.EPISODE)
                .position(BigDecimal.valueOf(0))
                .build();
        item.setId(id);
        return item;
    }

    @Test
    void getPlayQueueDelegatesToService() {
        UUID id = UUID.randomUUID();
        PlayQueueEntity queue = PlayQueueEntity.builder().build();
        when(playQueueService.getPlayQueue(id, authentication)).thenReturn(Optional.of(queue));

        Optional<PlayQueueEntity> result = subject.getPlayQueue(id, authentication);

        assertTrue(result.isPresent());
        verify(playQueueService).getPlayQueue(id, authentication);
    }

    @Test
    void createPlayQueueDelegatesToService() {
        UUID showId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        CreatePlayQueueInput input = new CreatePlayQueueInput(PlayQueueSourceType.SHOW, showId, episodeId, true);
        PlayQueueEntity queue = PlayQueueEntity.builder().build();
        when(playQueueService.createPlayQueue(PlayQueueSourceType.SHOW, showId, episodeId, true, authentication)).thenReturn(queue);

        PlayQueueEntity result = subject.createPlayQueue(input, authentication);

        assertEquals(queue, result);
    }

    @Test
    void createPlayQueueDefaultsShuffleToFalse() {
        UUID movieId = UUID.randomUUID();
        CreatePlayQueueInput input = new CreatePlayQueueInput(PlayQueueSourceType.MOVIE, movieId, null, null);
        PlayQueueEntity queue = PlayQueueEntity.builder().build();
        when(playQueueService.createPlayQueue(PlayQueueSourceType.MOVIE, movieId, null, false, authentication)).thenReturn(queue);

        PlayQueueEntity result = subject.createPlayQueue(input, authentication);

        assertEquals(queue, result);
    }

    @Test
    void movePlayQueueItemDelegatesToService() {
        UUID queueId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID afterId = UUID.randomUUID();
        PlayQueueEntity queue = PlayQueueEntity.builder().build();
        when(playQueueService.movePlayQueueItem(queueId, itemId, afterId, authentication)).thenReturn(queue);

        PlayQueueEntity result = subject.movePlayQueueItem(queueId, itemId, afterId, authentication);

        assertEquals(queue, result);
        verify(playbackCommandService).publishQueueChanged(queueId);
    }

    @Test
    void removePlayQueueItemDelegatesToService() {
        UUID queueId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        PlayQueueEntity queue = PlayQueueEntity.builder().build();
        when(playQueueService.removePlayQueueItem(queueId, itemId, authentication)).thenReturn(queue);

        PlayQueueEntity result = subject.removePlayQueueItem(queueId, itemId, authentication);

        assertEquals(queue, result);
        verify(playbackCommandService).publishQueueChanged(queueId);
    }

    @Test
    void addPlayQueueItemDelegatesToService() {
        UUID queueId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        PlayQueueEntity queue = PlayQueueEntity.builder().build();
        when(playQueueService.addPlayQueueItem(queueId, MediaType.TRACK, mediaId, null, authentication)).thenReturn(queue);

        PlayQueueEntity result = subject.addPlayQueueItem(queueId, MediaType.TRACK, mediaId, null, authentication);

        assertEquals(queue, result);
        verify(playbackCommandService).publishQueueChanged(queueId);
    }

    @Test
    void updatePlayQueueDelegatesToService() {
        UUID id = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        PlayQueueEntity queue = buildQueueWithUser();
        when(playQueueService.updatePlayQueue(id, 5000L, itemId, null, authentication)).thenReturn(Optional.of(queue));

        Optional<PlayQueueEntity> result = subject.updatePlayQueue(id, 5000L, itemId, null, null, authentication);

        assertTrue(result.isPresent());
        verify(playQueuePrefetchService).maybePrefetchNext(queue, itemId, 5000L);
    }

    @Test
    void updatePlayQueueMapsStreamSettings() {
        UUID id = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        PlayQueueEntity queue = buildQueueWithUser();
        StreamSettingsInput input = new StreamSettingsInput(true, false, SubtitleFormat.SRT);
        when(playQueueService.updatePlayQueue(id, 5000L, itemId,
                new PlayQueueService.StreamSettings(true, false, SubtitleFormat.SRT), authentication))
                .thenReturn(Optional.of(queue));

        Optional<PlayQueueEntity> result = subject.updatePlayQueue(id, 5000L, itemId, input, null, authentication);

        assertTrue(result.isPresent());
    }

    @Test
    void updatePlayQueuePublishesPlaybackHeartbeat() {
        UUID id = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        PlayQueueEntity queue = buildQueueWithUser();
        when(playQueueService.updatePlayQueue(id, 5000L, itemId, null, authentication)).thenReturn(Optional.of(queue));

        subject.updatePlayQueue(id, 5000L, itemId, null, PlayState.PAUSED, authentication);

        verify(playbackStatusService).publishHeartbeat(queue.getId(), itemId,
                queue.getUserEntity().getId(), "sub-123", "test-user", null, null, null, null, null, 5000L, PlayState.PAUSED);
    }

    @Test
    void updatePlayQueueRepublishesLastKnownSessionWhenDatabaseIsUnavailable() {
        UUID id = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(playQueueService.updatePlayQueue(id, 7000L, itemId, null, authentication))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("pool exhausted"));
        when(authentication.getName()).thenReturn("sub-123");
        when(playbackSessionRegistry.find(id)).thenReturn(Optional.of(
                app.ister.core.eventdata.PlaybackStatusData.builder()
                        .playQueueId(id).playQueueItemId(itemId).userId(userId)
                        .userExternalId("sub-123").userName("test-user")
                        .mediaType(MediaType.EPISODE).playState(PlayState.PLAYING).build()));

        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> subject.updatePlayQueue(id, 7000L, itemId, null, PlayState.PAUSED, authentication));

        // The now-playing feed still gets the fresh progress/state, from registry data.
        verify(playbackStatusService).publishHeartbeat(id, itemId, userId, "sub-123", "test-user",
                MediaType.EPISODE, null, null, null, null, 7000L, PlayState.PAUSED);
        verifyNoInteractions(playQueuePrefetchService);
    }

    @Test
    void updatePlayQueueSkipsDegradedHeartbeatWhenClientMovedToAnotherItem() {
        UUID id = UUID.randomUUID();
        UUID newItemId = UUID.randomUUID();
        when(playQueueService.updatePlayQueue(id, 7000L, newItemId, null, authentication))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("pool exhausted"));
        when(authentication.getName()).thenReturn("sub-123");
        when(playbackSessionRegistry.find(id)).thenReturn(Optional.of(
                app.ister.core.eventdata.PlaybackStatusData.builder()
                        .playQueueId(id).playQueueItemId(UUID.randomUUID())
                        .userExternalId("sub-123").userName("test-user")
                        .playState(PlayState.PLAYING).build()));

        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> subject.updatePlayQueue(id, 7000L, newItemId, null, PlayState.PLAYING, authentication));

        // The registry's media fields describe the previous item; publishing them with the
        // new item's progress would show the wrong track, so nothing is published.
        verifyNoInteractions(playbackStatusService);
    }

    @Test
    void updatePlayQueueSkipsDegradedHeartbeatForOtherUsersSession() {
        UUID id = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        when(playQueueService.updatePlayQueue(id, 7000L, itemId, null, authentication))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("pool exhausted"));
        when(authentication.getName()).thenReturn("sub-of-someone-else");
        when(playbackSessionRegistry.find(id)).thenReturn(Optional.of(
                app.ister.core.eventdata.PlaybackStatusData.builder()
                        .playQueueId(id).userExternalId("sub-123").userName("test-user")
                        .playState(PlayState.PLAYING).build()));

        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> subject.updatePlayQueue(id, 7000L, itemId, null, PlayState.PAUSED, authentication));

        verifyNoInteractions(playbackStatusService);
    }

    @Test
    void updatePlayQueueSkipsPrefetchWhenQueueNotFound() {
        UUID id = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        when(playQueueService.updatePlayQueue(id, 5000L, itemId, null, authentication)).thenReturn(Optional.empty());

        Optional<PlayQueueEntity> result = subject.updatePlayQueue(id, 5000L, itemId, null, null, authentication);

        assertTrue(result.isEmpty());
        verifyNoInteractions(playQueuePrefetchService, playbackStatusService);
    }

    @Test
    void currentItemIdReturnsCurrentItemFromPlayQueue() {
        UUID currentId = UUID.randomUUID();
        PlayQueueEntity queue = PlayQueueEntity.builder().currentItem(currentId).build();

        UUID result = subject.currentItemId(queue);

        assertEquals(currentId, result);
    }

    // --- playQueueItems windowing logic ---

    @Test
    void playQueueItemsReturnsFirst21WhenNoCurrentItem() {
        List<PlayQueueItemEntity> items = IntStream.range(0, 30)
                .mapToObj(i -> buildItem(UUID.randomUUID()))
                .toList();
        PlayQueueEntity queue = PlayQueueEntity.builder().items(new ArrayList<>(items)).build();

        List<PlayQueueItemEntity> result = subject.playQueueItems(queue);

        assertEquals(21, result.size());
        assertEquals(items.get(0), result.get(0));
    }

    @Test
    void playQueueItemsReturnsAllWhenLessThan21AndNoCurrentItem() {
        List<PlayQueueItemEntity> items = IntStream.range(0, 5)
                .mapToObj(i -> buildItem(UUID.randomUUID()))
                .toList();
        PlayQueueEntity queue = PlayQueueEntity.builder().items(new ArrayList<>(items)).build();

        List<PlayQueueItemEntity> result = subject.playQueueItems(queue);

        assertEquals(5, result.size());
    }

    @Test
    void playQueueItemsWindowsCenteredOnCurrentItem() {
        List<UUID> ids = IntStream.range(0, 25).mapToObj(i -> UUID.randomUUID()).toList();
        List<PlayQueueItemEntity> items = ids.stream().map(this::buildItem).toList();
        // current item is at index 12
        UUID currentId = ids.get(12);
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new ArrayList<>(items))
                .currentItem(currentId)
                .build();

        List<PlayQueueItemEntity> result = subject.playQueueItems(queue);

        // 10 before + current + 10 after = 21
        assertEquals(21, result.size());
        assertEquals(items.get(2), result.get(0));   // index 12 - 10 = 2
        assertEquals(items.get(12), result.get(10)); // current at index 10 in result
        assertEquals(items.get(22), result.get(20)); // index 12 + 10 = 22
    }

    @Test
    void playQueueItemsClipsToStartWhenCurrentIsNearBeginning() {
        List<UUID> ids = IntStream.range(0, 25).mapToObj(i -> UUID.randomUUID()).toList();
        List<PlayQueueItemEntity> items = ids.stream().map(this::buildItem).toList();
        // current item is at index 3
        UUID currentId = ids.get(3);
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new ArrayList<>(items))
                .currentItem(currentId)
                .build();

        List<PlayQueueItemEntity> result = subject.playQueueItems(queue);

        // from = max(0, 3-10) = 0, to = min(25, 3+11) = 14 → 14 items
        assertEquals(14, result.size());
        assertEquals(items.get(0), result.get(0));
    }

    @Test
    void playQueueItemsClipsToEndWhenCurrentIsNearEnd() {
        List<UUID> ids = IntStream.range(0, 25).mapToObj(i -> UUID.randomUUID()).toList();
        List<PlayQueueItemEntity> items = ids.stream().map(this::buildItem).toList();
        // current item is at index 22
        UUID currentId = ids.get(22);
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new ArrayList<>(items))
                .currentItem(currentId)
                .build();

        List<PlayQueueItemEntity> result = subject.playQueueItems(queue);

        // from = max(0, 22-10) = 12, to = min(25, 22+11) = 25 → 13 items
        assertEquals(13, result.size());
        assertEquals(items.get(12), result.get(0));
        assertEquals(items.get(24), result.getLast());
    }

    @Test
    void playQueueItemsReturnsFirst21WhenCurrentItemNotFound() {
        List<UUID> ids = IntStream.range(0, 30).mapToObj(i -> UUID.randomUUID()).toList();
        List<PlayQueueItemEntity> items = ids.stream().map(this::buildItem).toList();
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new ArrayList<>(items))
                .currentItem(UUID.randomUUID()) // not in list
                .build();

        List<PlayQueueItemEntity> result = subject.playQueueItems(queue);

        assertEquals(21, result.size());
    }

    @Test
    void currentItemResolvesItemFromQueue() {
        UUID currentId = UUID.randomUUID();
        PlayQueueItemEntity current = buildItem(currentId);
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new ArrayList<>(List.of(buildItem(UUID.randomUUID()), current)))
                .currentItem(currentId)
                .build();

        Optional<PlayQueueItemEntity> result = subject.currentItem(queue);

        assertTrue(result.isPresent());
        assertEquals(current, result.get());
    }

    @Test
    void currentItemReturnsEmptyWhenNoCurrentItem() {
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new ArrayList<>(List.of(buildItem(UUID.randomUUID()))))
                .build();

        Optional<PlayQueueItemEntity> result = subject.currentItem(queue);

        assertTrue(result.isEmpty());
    }

    @Test
    void currentItemReturnsEmptyWhenCurrentItemNotInQueue() {
        PlayQueueEntity queue = PlayQueueEntity.builder()
                .items(new ArrayList<>(List.of(buildItem(UUID.randomUUID()))))
                .currentItem(UUID.randomUUID())
                .build();

        Optional<PlayQueueItemEntity> result = subject.currentItem(queue);

        assertTrue(result.isEmpty());
    }

    @Test
    void playQueueItemEpisodeReturnsEpisodeWhenEpisodeIdSet() {
        UUID episodeId = UUID.randomUUID();
        EpisodeEntity episode = EpisodeEntity.builder().number(1).build();
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.EPISODE)
                .position(BigDecimal.ZERO)
                .build();
        item.setEpisodeEntityId(episodeId);
        when(episodeRepository.findById(episodeId)).thenReturn(Optional.of(episode));

        Optional<EpisodeEntity> result = subject.playQueueItemEpisode(item);

        assertTrue(result.isPresent());
    }

    @Test
    void playQueueItemEpisodeReturnsEmptyWhenNoEpisodeId() {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.MOVIE)
                .position(BigDecimal.ZERO)
                .build();

        Optional<EpisodeEntity> result = subject.playQueueItemEpisode(item);

        assertTrue(result.isEmpty());
        verifyNoInteractions(episodeRepository);
    }

    @Test
    void playQueueItemMovieReturnsMovieWhenMovieIdSet() {
        UUID movieId = UUID.randomUUID();
        MovieEntity movie = MovieEntity.builder().name("Test").releaseYear(2020).build();
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.MOVIE)
                .position(BigDecimal.ZERO)
                .build();
        item.setMovieEntityId(movieId);
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        Optional<MovieEntity> result = subject.playQueueItemMovie(item);

        assertTrue(result.isPresent());
    }

    @Test
    void playQueueItemMovieReturnsEmptyWhenNoMovieId() {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.EPISODE)
                .position(BigDecimal.ZERO)
                .build();

        Optional<MovieEntity> result = subject.playQueueItemMovie(item);

        assertTrue(result.isEmpty());
        verifyNoInteractions(movieRepository);
    }

    // --- now-playing heartbeat: title / duration / artwork per media type ---

    /** Puts a single item in a queue and runs updatePlayQueue over it, as the client does. */
    private void updateWith(PlayQueueItemEntity item) {
        UUID id = UUID.randomUUID();
        PlayQueueEntity queue = buildQueueWithUser();
        queue.setItems(new ArrayList<>(List.of(item)));
        when(playQueueService.updatePlayQueue(id, 1000L, item.getId(), null, authentication))
                .thenReturn(Optional.of(queue));

        subject.updatePlayQueue(id, 1000L, item.getId(), null, PlayState.PLAYING, authentication);
    }

    private static PlayQueueItemEntity identified(PlayQueueItemEntity item) {
        item.setId(UUID.randomUUID());
        return item;
    }

    @Test
    void heartbeatForAMovieCarriesTitleLongestDurationAndCoverImage() {
        MovieEntity movie = MovieEntity.builder().name("Heat").releaseYear(1995).build();
        movie.setId(UUID.randomUUID());
        PlayQueueItemEntity item = identified(PlayQueueItemEntity.builder()
                .type(MediaType.MOVIE).position(BigDecimal.ZERO).build());
        item.setMovieEntity(movie);

        ImageEntity background = ImageEntity.builder().type(ImageType.BACKGROUND).build();
        background.setId(UUID.randomUUID());
        ImageEntity cover = ImageEntity.builder().type(ImageType.COVER).build();
        cover.setId(UUID.randomUUID());
        when(mediaFileRepository.findByMovieEntityId(movie.getId())).thenReturn(List.of(
                MediaFileEntity.builder().durationInMilliseconds(0).build(),
                MediaFileEntity.builder().durationInMilliseconds(90_000L).build(),
                MediaFileEntity.builder().durationInMilliseconds(120_000L).build()));
        when(imageRepository.findByMovieEntityId(movie.getId())).thenReturn(List.of(background, cover));

        updateWith(item);

        verify(playbackStatusService).publishHeartbeat(any(), eq(item.getId()), any(), eq("sub-123"),
                eq("test-user"), eq(MediaType.MOVIE), eq(movie.getId()), eq("Heat"), eq(120_000L),
                eq(cover.getId()), eq(1000L), eq(PlayState.PLAYING));
    }

    /** An episode without a still of its own borrows the show's image. */
    @Test
    void heartbeatForAnEpisodeFallsBackToTheShowImage() {
        ShowEntity show = ShowEntity.builder().name("The Wire").build();
        show.setId(UUID.randomUUID());
        SeasonEntity season = SeasonEntity.builder().number(1).showEntity(show).build();
        EpisodeEntity episode = EpisodeEntity.builder().number(2).showEntity(show).seasonEntity(season).build();
        episode.setId(UUID.randomUUID());
        PlayQueueItemEntity item = identified(PlayQueueItemEntity.builder()
                .type(MediaType.EPISODE).position(BigDecimal.ZERO).build());
        item.setEpisodeEntity(episode);

        ImageEntity showImage = ImageEntity.builder().type(ImageType.BACKGROUND).build();
        showImage.setId(UUID.randomUUID());
        when(mediaFileRepository.findByEpisodeEntityId(episode.getId())).thenReturn(List.of());
        when(imageRepository.findByEpisodeEntityId(episode.getId())).thenReturn(List.of());
        when(imageRepository.findByShowEntityId(show.getId())).thenReturn(List.of(showImage));

        updateWith(item);

        // No COVER among the images, so the first image is used.
        verify(playbackStatusService).publishHeartbeat(any(), eq(item.getId()), any(), eq("sub-123"),
                eq("test-user"), eq(MediaType.EPISODE), eq(episode.getId()), eq("The Wire S01E02"),
                isNull(), eq(showImage.getId()), eq(1000L), eq(PlayState.PLAYING));
    }

    @Test
    void heartbeatForATrackUsesTheMetadataTitleAndAlbumCover() {
        AlbumEntity album = AlbumEntity.builder().name("Kid A").build();
        album.setId(UUID.randomUUID());
        TrackEntity track = TrackEntity.builder().number(3).albumEntity(album)
                .metadataEntities(List.of(MetadataEntity.builder().title("Idioteque").build())).build();
        track.setId(UUID.randomUUID());
        PlayQueueItemEntity item = identified(PlayQueueItemEntity.builder()
                .type(MediaType.TRACK).position(BigDecimal.ZERO).build());
        item.setTrackEntityId(track.getId());

        ImageEntity cover = ImageEntity.builder().type(ImageType.COVER).build();
        cover.setId(UUID.randomUUID());
        when(trackRepository.findById(track.getId())).thenReturn(Optional.of(track));
        when(mediaFileRepository.findByTrackEntityId(track.getId()))
                .thenReturn(List.of(MediaFileEntity.builder().durationInMilliseconds(240_000L).build()));
        when(imageRepository.findByAlbumEntityId(album.getId())).thenReturn(List.of(cover));

        updateWith(item);

        verify(playbackStatusService).publishHeartbeat(any(), eq(item.getId()), any(), eq("sub-123"),
                eq("test-user"), eq(MediaType.TRACK), eq(track.getId()), eq("Idioteque"), eq(240_000L),
                eq(cover.getId()), eq(1000L), eq(PlayState.PLAYING));
    }

    /** A chapter without metadata is named after its book. */
    @Test
    void heartbeatForAChapterFallsBackToTheBookName() {
        BookEntity book = BookEntity.builder().name("Dit zijn de namen").build();
        book.setId(UUID.randomUUID());
        ChapterEntity chapter = ChapterEntity.builder().number(4).bookEntity(book)
                .metadataEntities(List.of()).build();
        chapter.setId(UUID.randomUUID());
        PlayQueueItemEntity item = identified(PlayQueueItemEntity.builder()
                .type(MediaType.CHAPTER).position(BigDecimal.ZERO).build());
        item.setChapterEntityId(chapter.getId());

        when(chapterRepository.findById(chapter.getId())).thenReturn(Optional.of(chapter));
        when(mediaFileRepository.findByChapterEntityId(chapter.getId())).thenReturn(List.of());
        when(imageRepository.findByBookEntityId(book.getId())).thenReturn(List.of());

        updateWith(item);

        verify(playbackStatusService).publishHeartbeat(any(), eq(item.getId()), any(), eq("sub-123"),
                eq("test-user"), eq(MediaType.CHAPTER), eq(chapter.getId()),
                eq("Dit zijn de namen – chapter 4"), isNull(), isNull(), eq(1000L), eq(PlayState.PLAYING));
    }

    /** An episode the feed gave no image gets the podcast cover, and its title falls back to the podcast. */
    @Test
    void heartbeatForAPodcastEpisodeFallsBackToThePodcast() {
        PodcastEntity podcast = PodcastEntity.builder().title("Serial")
                .feedUrl("https://example.org/feed").build();
        podcast.setId(UUID.randomUUID());
        PodcastEpisodeEntity episode = PodcastEpisodeEntity.builder().podcastEntity(podcast)
                .guid("ep-1").enclosureUrl("https://example.org/ep-1.mp3").metadataEntities(List.of()).build();
        episode.setId(UUID.randomUUID());
        PlayQueueItemEntity item = identified(PlayQueueItemEntity.builder()
                .type(MediaType.PODCAST_EPISODE).position(BigDecimal.ZERO).build());
        item.setPodcastEpisodeEntityId(episode.getId());

        ImageEntity podcastCover = ImageEntity.builder().type(ImageType.COVER).build();
        podcastCover.setId(UUID.randomUUID());
        when(podcastEpisodeRepository.findById(episode.getId())).thenReturn(Optional.of(episode));
        when(mediaFileRepository.findByPodcastEpisodeEntityId(episode.getId()))
                .thenReturn(List.of(MediaFileEntity.builder().durationInMilliseconds(3_600_000L).build()));
        when(imageRepository.findByPodcastEpisodeEntityId(episode.getId())).thenReturn(List.of());
        when(imageRepository.findByPodcastEntityId(podcast.getId())).thenReturn(List.of(podcastCover));

        updateWith(item);

        verify(playbackStatusService).publishHeartbeat(any(), eq(item.getId()), any(), eq("sub-123"),
                eq("test-user"), eq(MediaType.PODCAST_EPISODE), eq(episode.getId()), eq("Serial"),
                eq(3_600_000L), eq(podcastCover.getId()), eq(1000L), eq(PlayState.PLAYING));
    }

    /** An epub is not playable, so it carries no media id, title, duration or artwork. */
    @Test
    void heartbeatForABookItemCarriesNoMediaDetails() {
        PlayQueueItemEntity item = identified(PlayQueueItemEntity.builder()
                .type(MediaType.BOOK).position(BigDecimal.ZERO).build());

        updateWith(item);

        verify(playbackStatusService).publishHeartbeat(any(), eq(item.getId()), any(), eq("sub-123"),
                eq("test-user"), eq(MediaType.BOOK), isNull(), isNull(), isNull(), isNull(),
                eq(1000L), eq(PlayState.PLAYING));
        verifyNoInteractions(mediaFileRepository, imageRepository);
    }

    /** The heartbeat still goes out when the updated item is no longer in the (trimmed) queue. */
    @Test
    void heartbeatWithoutAMatchingQueueItemHasNoMediaDetails() {
        UUID id = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        PlayQueueEntity queue = buildQueueWithUser();
        queue.setItems(null);
        when(playQueueService.updatePlayQueue(id, 1000L, itemId, null, authentication))
                .thenReturn(Optional.of(queue));

        subject.updatePlayQueue(id, 1000L, itemId, null, PlayState.PLAYING, authentication);

        verify(playbackStatusService).publishHeartbeat(queue.getId(), itemId, queue.getUserEntity().getId(),
                "sub-123", "test-user", null, null, null, null, null, 1000L, PlayState.PLAYING);
    }

    // --- PlayQueueItem schema mappings for the audio types ---

    @Test
    void playQueueItemTrackReturnsTrackWhenTrackIdSet() {
        UUID trackId = UUID.randomUUID();
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.TRACK).position(BigDecimal.ZERO).build();
        item.setTrackEntityId(trackId);
        when(trackRepository.findById(trackId)).thenReturn(Optional.of(TrackEntity.builder().number(1).build()));

        assertTrue(subject.playQueueItemTrack(item).isPresent());
    }

    @Test
    void playQueueItemTrackReturnsEmptyWhenNoTrackId() {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.MOVIE).position(BigDecimal.ZERO).build();

        assertTrue(subject.playQueueItemTrack(item).isEmpty());
        verifyNoInteractions(trackRepository);
    }

    @Test
    void playQueueItemChapterReturnsChapterWhenChapterIdSet() {
        UUID chapterId = UUID.randomUUID();
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.CHAPTER).position(BigDecimal.ZERO).build();
        item.setChapterEntityId(chapterId);
        when(chapterRepository.findById(chapterId))
                .thenReturn(Optional.of(ChapterEntity.builder().number(1).build()));

        assertTrue(subject.playQueueItemChapter(item).isPresent());
    }

    @Test
    void playQueueItemChapterReturnsEmptyWhenNoChapterId() {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.MOVIE).position(BigDecimal.ZERO).build();

        assertTrue(subject.playQueueItemChapter(item).isEmpty());
        verifyNoInteractions(chapterRepository);
    }

    @Test
    void playQueueItemPodcastEpisodeReturnsEpisodeWhenIdSet() {
        UUID episodeId = UUID.randomUUID();
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.PODCAST_EPISODE).position(BigDecimal.ZERO).build();
        item.setPodcastEpisodeEntityId(episodeId);
        when(podcastEpisodeRepository.findById(episodeId)).thenReturn(Optional.of(
                PodcastEpisodeEntity.builder().guid("ep-1").enclosureUrl("https://example.org/ep-1.mp3").build()));

        assertTrue(subject.playQueueItemPodcastEpisode(item).isPresent());
    }

    @Test
    void playQueueItemPodcastEpisodeReturnsEmptyWhenNoId() {
        PlayQueueItemEntity item = PlayQueueItemEntity.builder()
                .type(MediaType.MOVIE).position(BigDecimal.ZERO).build();

        assertTrue(subject.playQueueItemPodcastEpisode(item).isEmpty());
        verifyNoInteractions(podcastEpisodeRepository);
    }
}
