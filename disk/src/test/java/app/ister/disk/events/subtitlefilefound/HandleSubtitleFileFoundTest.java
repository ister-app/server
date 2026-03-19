package app.ister.disk.events.subtitlefilefound;

import app.ister.core.entity.DirectoryEntity;
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

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
