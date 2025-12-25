package app.ister.worker.scanner;

import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.entitiy.ImageEntity;
import app.ister.core.entitiy.MediaFileEntity;
import app.ister.core.entitiy.OtherPathFileEntity;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.OtherPathFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScannedCacheTest {
    ScannedCache subject;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private MediaFileRepository mediaFileRepository;
    @Mock
    private OtherPathFileRepository otherPathFileRepository;

    @Test
    void happyFlow() {
        DirectoryEntity directoryEntity = DirectoryEntity.builder().build();

        ImageEntity imageEntity = ImageEntity.builder().path("/path").build();
        when(imageRepository.findByDirectoryEntity(directoryEntity)).thenReturn(new ArrayList<>(List.of(imageEntity)));
        MediaFileEntity mediaFileEntity = MediaFileEntity.builder().path("/path2").build();
        when(mediaFileRepository.findByDirectoryEntity(directoryEntity)).thenReturn(new ArrayList<>(List.of(mediaFileEntity)));
        OtherPathFileEntity otherPathFileEntity = OtherPathFileEntity.builder().path("/path3").build();
        when(otherPathFileRepository.findByDirectoryEntity(directoryEntity)).thenReturn(new ArrayList<>(List.of(otherPathFileEntity)));

        subject = new ScannedCache(directoryEntity, imageRepository, mediaFileRepository, otherPathFileRepository);
        subject.foundPath("/path");
        subject.foundPath("/path3");
        subject.removeNotScannedFilesFromDatabase();

        verify(imageRepository).deleteAll(List.of());
        verify(mediaFileRepository).deleteAll(List.of(mediaFileEntity));
        verify(otherPathFileRepository).deleteAll(List.of());
    }
}