package app.ister.disk.events.subtitlefilefound;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.entity.OtherPathFileEntity;
import app.ister.core.enums.PathFileType;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.repository.OtherPathFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SidecarSubtitleLinkerTest {
    private static final String MEDIA = "/disk/shows/Show (2024)/Season 01/s01e01.mkv";
    private static final String SUBTITLE = "/disk/shows/Show (2024)/Season 01/s01e01.nl.srt";

    @InjectMocks
    private SidecarSubtitleLinker subject;

    @Mock
    private MediaFileStreamRepository mediaFileStreamRepository;

    @Mock
    private OtherPathFileRepository otherPathFileRepository;

    private final DirectoryEntity directory = DirectoryEntity.builder().id(UUID.randomUUID()).build();
    private final MediaFileEntity mediaFile = MediaFileEntity.builder().id(UUID.randomUUID()).path(MEDIA).build();

    @Test
    void linkUpsertsTheStreamAndPointsTheSidecarAtIt() {
        MediaFileStreamEntity stream = MediaFileStreamEntity.builder().build();
        OtherPathFileEntity sidecar = OtherPathFileEntity.builder().path(SUBTITLE).pathFileType(PathFileType.SUBTITLE).build();
        when(mediaFileStreamRepository.findByMediaFileEntity_IdAndStreamIndexAndPath(mediaFile.getId(), 0, SUBTITLE))
                .thenReturn(Optional.of(stream));
        when(otherPathFileRepository.findByDirectoryEntityAndPath(directory, SUBTITLE)).thenReturn(Optional.of(sidecar));

        subject.link(directory, mediaFile, SUBTITLE);

        ArgumentCaptor<MediaFileStreamRepository.StreamUpsert> upsert = ArgumentCaptor.forClass(MediaFileStreamRepository.StreamUpsert.class);
        verify(mediaFileStreamRepository).upsert(upsert.capture());
        assertEquals("EXTERNAL_SUBTITLE", upsert.getValue().codecType());
        assertEquals("subtitle srt", upsert.getValue().codecName());
        assertEquals("nld", upsert.getValue().language());
        assertEquals(mediaFile.getId(), upsert.getValue().mediaFileEntityId());
        assertEquals(SUBTITLE, upsert.getValue().path());
        assertEquals(0, upsert.getValue().streamIndex());
        assertEquals(stream, sidecar.getMediaFileStreamEntity());
        verify(otherPathFileRepository).save(sidecar);
    }

    @Test
    void linkAllForLinksOnlyTheSidecarsOfThisMediaFile() {
        String english = "/disk/shows/Show (2024)/Season 01/s01e01.en.srt";
        OtherPathFileEntity dutch = OtherPathFileEntity.builder().path(SUBTITLE).pathFileType(PathFileType.SUBTITLE).build();
        OtherPathFileEntity englishFile = OtherPathFileEntity.builder().path(english).pathFileType(PathFileType.SUBTITLE).build();
        when(otherPathFileRepository.findByDirectoryEntityIdAndPathFileTypeAndPathStartingWith(
                directory.getId(), PathFileType.SUBTITLE, "/disk/shows/Show (2024)/Season 01/s01e01"))
                .thenReturn(List.of(dutch, englishFile));

        subject.linkAllFor(directory, mediaFile);

        verify(mediaFileStreamRepository, times(2)).upsert(any());
    }

    @Test
    void linkAllForDoesNothingWithoutSidecars() {
        when(otherPathFileRepository.findByDirectoryEntityIdAndPathFileTypeAndPathStartingWith(any(), any(), any()))
                .thenReturn(List.of());

        subject.linkAllFor(directory, mediaFile);

        verify(mediaFileStreamRepository, never()).upsert(any());
    }
}
