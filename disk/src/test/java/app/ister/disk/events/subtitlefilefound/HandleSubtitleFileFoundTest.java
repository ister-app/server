package app.ister.disk.events.subtitlefilefound;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.SubtitleFileFoundData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.service.ScannerHelperService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
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
    private MediaFileStreamRepository mediaFileStreamRepository;

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

        assertTrue(subject.handle(data));
    }

    @Test
    void handleWithEpisodePathThatMatchesMediaFile() {
        UUID dirId = UUID.randomUUID();
        LibraryEntity library = LibraryEntity.builder().build();
        DirectoryEntity directoryEntity = DirectoryEntity.builder().id(dirId).libraryEntity(library).build();
        MediaFileEntity mediaFileEntity = MediaFileEntity.builder()
                .directoryEntity(directoryEntity)
                .path("/disk/shows/Show (2024)/Season 01/s01e01.mkv")
                .build();
        EpisodeEntity episodeEntity = EpisodeEntity.builder()
                .mediaFileEntities(List.of(mediaFileEntity))
                .build();
        SubtitleFileFoundData data = SubtitleFileFoundData.builder()
                .directoryEntityUUID(dirId)
                .path("/disk/shows/Show (2024)/Season 01/s01e01.nl.srt")
                .build();

        when(directoryRepository.findById(dirId)).thenReturn(Optional.of(directoryEntity));
        when(scannerHelperService.getOrCreateEpisode(any(), any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(episodeEntity);

        assertTrue(subject.handle(data));

        verify(mediaFileStreamRepository).save(any());
    }
}
