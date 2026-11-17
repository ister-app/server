package app.ister.disk.events.subtitlefilefound;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.SubtitleFileFoundData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.ScannerHelperService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleSubtitleFileFoundTest {

    @InjectMocks
    private HandleSubtitleFileFound subject;

    @Mock
    private DirectoryRepository directoryRepository;

    @Mock
    private ScannerHelperService scannerHelperService;

    @Mock
    private MediaFileRepository mediaFileRepository;

    @Mock
    private SidecarSubtitleLinker sidecarSubtitleLinker;

    @Test
    void handles() {
        assertEquals(EventType.SUBTITLE_FILE_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        SubtitleFileFoundData data = SubtitleFileFoundData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleWithNonEpisodePath() {
        UUID uuid = UUID.randomUUID();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(uuid).build();
        SubtitleFileFoundData data = SubtitleFileFoundData.builder()
                .directoryEntityUUID(uuid)
                .path("/disk/shows/Show (2024)/tvshow.srt")
                .build();

        when(directoryRepository.findById(uuid)).thenReturn(Optional.of(directoryEntity));

        subject.handle(data);

        // A non-episode path must not touch the episode/stream/other-file collaborators.
        verifyNoInteractions(scannerHelperService, sidecarSubtitleLinker);
    }

    @Test
    void handleLinksTheSubtitleToTheMatchingMediaFileOfItsDirectory() {
        UUID dirId = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(dirId).libraryEntity(library).build();
        MediaFileEntity matching = MediaFileEntity.builder()
                .path("/disk/shows/Show (2024)/Season 01/s01e01.mkv").build();
        matching.setDirectoryEntity(directoryEntity);
        MediaFileEntity otherCut = MediaFileEntity.builder()
                .path("/disk/shows/Show (2024)/Season 01/s01e01 - extended.mkv").build();
        otherCut.setDirectoryEntity(directoryEntity);
        MediaFileEntity otherDirectory = MediaFileEntity.builder()
                .path("/disk/shows/Show (2024)/Season 01/s01e01.mkv").build();
        otherDirectory.setDirectoryEntity(DirectoryEntity.builder().id(UUID.randomUUID()).build());
        UUID episodeId = UUID.randomUUID();
        String subtitlePath = "/disk/shows/Show (2024)/Season 01/s01e01.nl.srt";
        SubtitleFileFoundData data = SubtitleFileFoundData.builder()
                .directoryEntityUUID(dirId).path(subtitlePath).build();

        when(directoryRepository.findById(dirId)).thenReturn(Optional.of(directoryEntity));
        when(scannerHelperService.getOrCreateEpisode(any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(EpisodeEntity.builder().id(episodeId).build());
        when(mediaFileRepository.findByEpisodeEntityId(episodeId)).thenReturn(List.of(matching, otherCut, otherDirectory));

        subject.handle(data);

        verify(sidecarSubtitleLinker).link(directoryEntity, matching, subtitlePath);
        verifyNoMoreInteractions(sidecarSubtitleLinker);
    }

    /** The media file is scanned later; its own analysis links the subtitle then. */
    @Test
    void handleLinksNothingWhileTheMediaFileIsNotThereYet() {
        UUID dirId = UUID.randomUUID();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(dirId).libraryEntity(LibraryEntity.builder().build()).build();
        UUID episodeId = UUID.randomUUID();
        SubtitleFileFoundData data = SubtitleFileFoundData.builder()
                .directoryEntityUUID(dirId).path("/disk/shows/Show (2024)/Season 01/s01e01.nl.srt").build();

        when(directoryRepository.findById(dirId)).thenReturn(Optional.of(directoryEntity));
        when(scannerHelperService.getOrCreateEpisode(any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(EpisodeEntity.builder().id(episodeId).build());
        when(mediaFileRepository.findByEpisodeEntityId(episodeId)).thenReturn(List.of());

        subject.handle(data);

        verifyNoInteractions(sidecarSubtitleLinker);
    }
}
