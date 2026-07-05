package app.ister.worker.events.albumfound;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.AlbumFoundData;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.worker.events.musicbrainz.MusicBrainzService;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.tmdbmetadata.ImageSave;
import app.ister.core.service.ServerEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDate;
import java.time.Month;
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
class HandleAlbumFoundTest {

    private static final String COVER_URL = "https://coverartarchive.org/release/mbid/front";

    @Mock
    private ServerEventService serverEventServiceMock;

    @InjectMocks
    private HandleAlbumFound subject;

    @Mock
    private AlbumRepository albumRepository;

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private MetadataRepository metadataRepository;

    @Mock
    private MusicBrainzService musicBrainzService;

    @Mock
    private ImageDownloadService imageDownloadService;

    private final UUID albumId = UUID.randomUUID();
    private final AlbumEntity album = AlbumEntity.builder()
            .id(albumId)
            .name("Album")
            .personEntity(PersonEntity.builder().name("Artist").build())
            .build();
    private final AlbumFoundData data = AlbumFoundData.builder()
            .eventType(EventType.ALBUM_FOUND)
            .albumId(albumId)
            .build();

    @Test
    void handles() {
        assertEquals(EventType.ALBUM_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        AlbumFoundData wrongData = AlbumFoundData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(wrongData));
    }

    @Test
    void listenerCallsHandleWithCorrectEventType() {
        when(albumRepository.findById(albumId)).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> subject.listener(data));
    }

    @Test
    void handleDoesNothingWhenAlbumNotFound() {
        when(albumRepository.findById(albumId)).thenReturn(Optional.empty());

        subject.handle(data);

        verifyNoInteractions(imageRepository, metadataRepository, musicBrainzService, imageDownloadService);
    }

    @Test
    void handleDownloadsCoverAndSavesMetadata() throws IOException {
        when(albumRepository.findById(albumId)).thenReturn(Optional.of(album));
        when(imageRepository.findByAlbumEntityId(albumId)).thenReturn(List.of());
        when(musicBrainzService.getCoverArtUrl("Artist", "Album")).thenReturn(Optional.of(COVER_URL));
        when(metadataRepository.findByAlbumEntityId(albumId)).thenReturn(List.of());
        when(musicBrainzService.getAlbumInfo("Artist", "Album"))
                .thenReturn(Optional.of(new MusicBrainzService.AlbumInfo("A great album")));

        subject.handle(data);

        verify(imageDownloadService).downloadAndSave(
                COVER_URL, ImageType.COVER, "eng",
                "MusicBrainz://" + COVER_URL,
                new ImageSave.MediaEntityRef(null, null, null, null, album));
        ArgumentCaptor<MetadataEntity> captor = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(captor.capture());
        MetadataEntity saved = captor.getValue();
        assertEquals("A great album", saved.getDescription());
        assertEquals(album, saved.getAlbumEntity());
        assertEquals("musicbrainz://album/Album", saved.getSourceUri());
    }

    @Test
    void handleSwallowsImageDownloadFailureAndStillSavesMetadata() throws IOException {
        when(albumRepository.findById(albumId)).thenReturn(Optional.of(album));
        when(imageRepository.findByAlbumEntityId(albumId)).thenReturn(List.of());
        when(musicBrainzService.getCoverArtUrl("Artist", "Album")).thenReturn(Optional.of(COVER_URL));
        when(metadataRepository.findByAlbumEntityId(albumId)).thenReturn(List.of());
        when(musicBrainzService.getAlbumInfo("Artist", "Album"))
                .thenReturn(Optional.of(new MusicBrainzService.AlbumInfo("A great album")));
        doThrow(new IOException("download failed"))
                .when(imageDownloadService).downloadAndSave(anyString(), any(), anyString(), anyString(), any());

        assertDoesNotThrow(() -> subject.handle(data));

        verify(metadataRepository).save(any(MetadataEntity.class));
    }

    @Test
    void handleSkipsCoverDownloadWhenImageAlreadyExists() {
        when(albumRepository.findById(albumId)).thenReturn(Optional.of(album));
        when(imageRepository.findByAlbumEntityId(albumId)).thenReturn(List.of(ImageEntity.builder().build()));
        when(metadataRepository.findByAlbumEntityId(albumId)).thenReturn(List.of());
        when(musicBrainzService.getAlbumInfo("Artist", "Album")).thenReturn(Optional.empty());

        subject.handle(data);

        verify(musicBrainzService, never()).getCoverArtUrl(anyString(), anyString());
        verifyNoInteractions(imageDownloadService);
        verify(metadataRepository, never()).save(any());
    }

    @Test
    void handleSkipsMetadataFetchWhenDescriptionAlreadyExists() {
        when(albumRepository.findById(albumId)).thenReturn(Optional.of(album));
        when(imageRepository.findByAlbumEntityId(albumId)).thenReturn(List.of(ImageEntity.builder().build()));
        when(metadataRepository.findByAlbumEntityId(albumId)).thenReturn(List.of(
                MetadataEntity.builder().description("Already there").build()));

        subject.handle(data);

        verifyNoInteractions(musicBrainzService, imageDownloadService);
        verify(metadataRepository, never()).save(any());
        verify(metadataRepository, never()).deleteAll(any());
    }

    @Test
    void handleMergesDescriptionIntoExistingMetadataWithoutDescription() {
        MetadataEntity existing = MetadataEntity.builder()
                .title("Album Title")
                .released(LocalDate.of(2020, Month.JANUARY, 1))
                .genre("Rock")
                .language("eng")
                .sourceUri("tag://album")
                .build();
        when(albumRepository.findById(albumId)).thenReturn(Optional.of(album));
        when(imageRepository.findByAlbumEntityId(albumId)).thenReturn(List.of(ImageEntity.builder().build()));
        when(metadataRepository.findByAlbumEntityId(albumId)).thenReturn(List.of(existing));
        when(musicBrainzService.getAlbumInfo("Artist", "Album"))
                .thenReturn(Optional.of(new MusicBrainzService.AlbumInfo("New description")));

        subject.handle(data);

        verify(metadataRepository).deleteAll(List.of(existing));
        ArgumentCaptor<MetadataEntity> captor = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(captor.capture());
        MetadataEntity saved = captor.getValue();
        assertEquals("Album Title", saved.getTitle());
        assertEquals("New description", saved.getDescription());
        assertEquals(LocalDate.of(2020, Month.JANUARY, 1), saved.getReleased());
        assertEquals("Rock", saved.getGenre());
        assertEquals("eng", saved.getLanguage());
        assertEquals("tag://album", saved.getSourceUri());
        assertEquals(album, saved.getAlbumEntity());
    }

    @Test
    void handleDoesNotSaveWhenMusicBrainzHasNothing() {
        when(albumRepository.findById(albumId)).thenReturn(Optional.of(album));
        when(imageRepository.findByAlbumEntityId(albumId)).thenReturn(List.of());
        when(musicBrainzService.getCoverArtUrl("Artist", "Album")).thenReturn(Optional.empty());
        when(metadataRepository.findByAlbumEntityId(albumId)).thenReturn(List.of());
        when(musicBrainzService.getAlbumInfo("Artist", "Album")).thenReturn(Optional.empty());

        subject.handle(data);

        verifyNoInteractions(imageDownloadService);
        verify(metadataRepository, never()).save(any());
    }
}
