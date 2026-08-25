package app.ister.api.controller;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScannerControllerTest {

    @InjectMocks
    private ScannerController subject;

    @Mock
    private MessageSender messageSender;

    @Mock
    private DirectoryRepository directoryRepository;

    @Test
    void scanLibrariesSendsMessageForEachLibraryDirectory() {
        DirectoryEntity dir1 = DirectoryEntity.builder()
                .name("movies")
                .path("/movies")
                .directoryType(DirectoryType.LIBRARY)
                .build();
        dir1.setId(UUID.randomUUID());
        DirectoryEntity dir2 = DirectoryEntity.builder()
                .name("shows")
                .path("/shows")
                .directoryType(DirectoryType.LIBRARY)
                .build();
        dir2.setId(UUID.randomUUID());

        when(directoryRepository.findByDirectoryType(DirectoryType.LIBRARY)).thenReturn(List.of(dir1, dir2));

        Boolean result = subject.scanLibraries(null);

        assertTrue(result);
        verify(messageSender, times(2)).sendNewDirectoriesScanRequested(any(), any());
    }

    @Test
    void scanLibrariesWithLibraryScansOnlyItsDirectories() {
        UUID libraryId = UUID.randomUUID();
        DirectoryEntity dir = DirectoryEntity.builder()
                .name("movies")
                .path("/movies")
                .directoryType(DirectoryType.LIBRARY)
                .build();
        dir.setId(UUID.randomUUID());

        when(directoryRepository.findByDirectoryTypeAndLibraryEntityId(DirectoryType.LIBRARY, libraryId))
                .thenReturn(List.of(dir));

        Boolean result = subject.scanLibraries(libraryId);

        assertTrue(result);
        verify(messageSender).sendNewDirectoriesScanRequested(any(), eq("movies"));
        verify(directoryRepository, never()).findByDirectoryType(any());
    }

    @Test
    void scanLibrariesReturnsTrueWhenNoDirectories() {
        when(directoryRepository.findByDirectoryType(DirectoryType.LIBRARY)).thenReturn(List.of());

        Boolean result = subject.scanLibraries(null);

        assertTrue(result);
        verify(messageSender, never()).sendNewDirectoriesScanRequested(any(), any());
    }
}
