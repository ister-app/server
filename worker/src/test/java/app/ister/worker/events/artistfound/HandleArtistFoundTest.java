package app.ister.worker.events.artistfound;

import app.ister.core.entity.ArtistEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.ArtistFoundData;
import app.ister.core.repository.ArtistRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.worker.events.musicbrainz.MusicBrainzService;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.tmdbmetadata.ImageSave;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleArtistFoundTest {

    private static final String IMAGE_URL = "https://upload.wikimedia.org/artist.jpg";

    @InjectMocks
    private HandleArtistFound subject;

    @Mock
    private ArtistRepository artistRepository;

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private MetadataRepository metadataRepository;

    @Mock
    private MusicBrainzService musicBrainzService;

    @Mock
    private ImageDownloadService imageDownloadService;

    private final UUID artistId = UUID.randomUUID();
    private final ArtistEntity artist = ArtistEntity.builder()
            .id(artistId)
            .name("Artist")
            .build();
    private final ArtistFoundData data = ArtistFoundData.builder()
            .eventType(EventType.ARTIST_FOUND)
            .artistId(artistId)
            .build();

    @Test
    void handles() {
        assertEquals(EventType.ARTIST_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        ArtistFoundData wrongData = ArtistFoundData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(wrongData));
    }

    @Test
    void listenerCallsHandleWithCorrectEventType() {
        when(artistRepository.findById(artistId)).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> subject.listener(data));
    }

    @Test
    void handleDoesNothingWhenArtistNotFound() {
        when(artistRepository.findById(artistId)).thenReturn(Optional.empty());

        subject.handle(data);

        verifyNoInteractions(imageRepository, metadataRepository, musicBrainzService, imageDownloadService);
    }

    @Test
    void handleSavesMetadataAndDownloadsImage() throws IOException {
        when(artistRepository.findById(artistId)).thenReturn(Optional.of(artist));
        when(metadataRepository.findByArtistEntityId(artistId)).thenReturn(List.of());
        when(imageRepository.findByArtistEntityId(artistId)).thenReturn(List.of());
        when(musicBrainzService.getArtistInfo("Artist"))
                .thenReturn(Optional.of(new MusicBrainzService.ArtistInfo("A bio", "rock", IMAGE_URL)));

        subject.handle(data);

        ArgumentCaptor<MetadataEntity> captor = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(captor.capture());
        MetadataEntity saved = captor.getValue();
        assertEquals("A bio", saved.getDescription());
        assertEquals("rock", saved.getGenre());
        assertEquals(artist, saved.getArtistEntity());
        assertEquals("musicbrainz://artist/Artist", saved.getSourceUri());
        verify(imageDownloadService).downloadAndSave(
                IMAGE_URL, ImageType.COVER, "eng",
                "wikipedia://" + IMAGE_URL,
                new ImageSave.MediaEntityRef(null, null, null, artist, null));
    }

    @Test
    void handleSkipsWhenMetadataAndImageAlreadyExist() {
        when(artistRepository.findById(artistId)).thenReturn(Optional.of(artist));
        when(metadataRepository.findByArtistEntityId(artistId))
                .thenReturn(List.of(MetadataEntity.builder().build()));
        when(imageRepository.findByArtistEntityId(artistId))
                .thenReturn(List.of(ImageEntity.builder().build()));

        subject.handle(data);

        verifyNoInteractions(musicBrainzService, imageDownloadService);
        verify(metadataRepository, never()).save(any());
    }

    @Test
    void handleSwallowsImageDownloadFailureAndStillSavesMetadata() throws IOException {
        when(artistRepository.findById(artistId)).thenReturn(Optional.of(artist));
        when(metadataRepository.findByArtistEntityId(artistId)).thenReturn(List.of());
        when(imageRepository.findByArtistEntityId(artistId)).thenReturn(List.of());
        when(musicBrainzService.getArtistInfo("Artist"))
                .thenReturn(Optional.of(new MusicBrainzService.ArtistInfo("A bio", "rock", IMAGE_URL)));
        doThrow(new IOException("download failed"))
                .when(imageDownloadService).downloadAndSave(anyString(), any(), anyString(), anyString(), any());

        assertDoesNotThrow(() -> subject.handle(data));

        verify(metadataRepository).save(any(MetadataEntity.class));
    }

    @Test
    void handleSavesOnlyMetadataWhenNoImageUrl() {
        when(artistRepository.findById(artistId)).thenReturn(Optional.of(artist));
        when(metadataRepository.findByArtistEntityId(artistId)).thenReturn(List.of());
        when(imageRepository.findByArtistEntityId(artistId)).thenReturn(List.of());
        when(musicBrainzService.getArtistInfo("Artist"))
                .thenReturn(Optional.of(new MusicBrainzService.ArtistInfo("A bio", "rock", null)));

        subject.handle(data);

        verify(metadataRepository).save(any(MetadataEntity.class));
        verifyNoInteractions(imageDownloadService);
    }

    @Test
    void handleDownloadsOnlyImageWhenMetadataAlreadyExists() throws IOException {
        when(artistRepository.findById(artistId)).thenReturn(Optional.of(artist));
        when(metadataRepository.findByArtistEntityId(artistId))
                .thenReturn(List.of(MetadataEntity.builder().build()));
        when(imageRepository.findByArtistEntityId(artistId)).thenReturn(List.of());
        when(musicBrainzService.getArtistInfo("Artist"))
                .thenReturn(Optional.of(new MusicBrainzService.ArtistInfo("A bio", "rock", IMAGE_URL)));

        subject.handle(data);

        verify(metadataRepository, never()).save(any());
        verify(imageDownloadService).downloadAndSave(
                IMAGE_URL, ImageType.COVER, "eng",
                "wikipedia://" + IMAGE_URL,
                new ImageSave.MediaEntityRef(null, null, null, artist, null));
    }

    @Test
    void handleDoesNotSaveMetadataWhenBioIsNull() throws IOException {
        when(artistRepository.findById(artistId)).thenReturn(Optional.of(artist));
        when(metadataRepository.findByArtistEntityId(artistId)).thenReturn(List.of());
        when(imageRepository.findByArtistEntityId(artistId)).thenReturn(List.of());
        when(musicBrainzService.getArtistInfo("Artist"))
                .thenReturn(Optional.of(new MusicBrainzService.ArtistInfo(null, "rock", IMAGE_URL)));

        subject.handle(data);

        verify(metadataRepository, never()).save(any());
        verify(imageDownloadService).downloadAndSave(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void handleDoesNothingWhenNoArtistInfoFound() {
        when(artistRepository.findById(artistId)).thenReturn(Optional.of(artist));
        when(metadataRepository.findByArtistEntityId(artistId)).thenReturn(List.of());
        when(imageRepository.findByArtistEntityId(artistId)).thenReturn(List.of());
        when(musicBrainzService.getArtistInfo("Artist")).thenReturn(Optional.empty());

        subject.handle(data);

        verify(metadataRepository, never()).save(any());
        verifyNoInteractions(imageDownloadService);
    }
}
