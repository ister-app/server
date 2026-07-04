package app.ister.worker.events.personfound;

import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.PersonFoundData;
import app.ister.core.repository.PersonRepository;
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
class HandlePersonFoundTest {

    private static final String IMAGE_URL = "https://upload.wikimedia.org/artist.jpg";

    @InjectMocks
    private HandlePersonFound subject;

    @Mock
    private PersonRepository personRepository;

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private MetadataRepository metadataRepository;

    @Mock
    private MusicBrainzService musicBrainzService;

    @Mock
    private ImageDownloadService imageDownloadService;

    private final UUID personId = UUID.randomUUID();
    private final PersonEntity artist = PersonEntity.builder()
            .id(personId)
            .name("Artist")
            .build();
    private final PersonFoundData data = PersonFoundData.builder()
            .eventType(EventType.PERSON_FOUND)
            .personId(personId)
            .build();

    @Test
    void handles() {
        assertEquals(EventType.PERSON_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        PersonFoundData wrongData = PersonFoundData.builder()
                .eventType(EventType.FILE_SCAN_REQUESTED)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(wrongData));
    }

    @Test
    void listenerCallsHandleWithCorrectEventType() {
        when(personRepository.findById(personId)).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> subject.listener(data));
    }

    @Test
    void handleDoesNothingWhenArtistNotFound() {
        when(personRepository.findById(personId)).thenReturn(Optional.empty());

        subject.handle(data);

        verifyNoInteractions(imageRepository, metadataRepository, musicBrainzService, imageDownloadService);
    }

    @Test
    void handleSavesMetadataAndDownloadsImage() throws IOException {
        when(personRepository.findById(personId)).thenReturn(Optional.of(artist));
        when(metadataRepository.findByPersonEntityId(personId)).thenReturn(List.of());
        when(imageRepository.findByPersonEntityId(personId)).thenReturn(List.of());
        when(musicBrainzService.getArtistInfo("Artist"))
                .thenReturn(Optional.of(new MusicBrainzService.ArtistInfo("A bio", "rock", IMAGE_URL, null, null)));

        subject.handle(data);

        ArgumentCaptor<MetadataEntity> captor = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository).save(captor.capture());
        MetadataEntity saved = captor.getValue();
        assertEquals("A bio", saved.getDescription());
        assertEquals("rock", saved.getGenre());
        assertEquals(artist, saved.getPersonEntity());
        assertEquals("musicbrainz://artist/Artist", saved.getSourceUri());
        verify(imageDownloadService).downloadAndSave(
                IMAGE_URL, ImageType.COVER, "eng",
                "wikipedia://" + IMAGE_URL,
                new ImageSave.MediaEntityRef(null, null, null, artist, null));
    }

    @Test
    void handleSetsBirthYearForPersonTypeArtist() {
        PersonEntity soloArtist = PersonEntity.builder().id(personId).name("Artist").build();
        when(personRepository.findById(personId)).thenReturn(Optional.of(soloArtist));
        when(metadataRepository.findByPersonEntityId(personId)).thenReturn(List.of());
        when(imageRepository.findByPersonEntityId(personId)).thenReturn(List.of());
        when(musicBrainzService.getArtistInfo("Artist"))
                .thenReturn(Optional.of(new MusicBrainzService.ArtistInfo("A bio", "rock", null, "Person", "1946-05-31")));

        subject.handle(data);

        assertEquals(1946, soloArtist.getBirthYear());
        verify(personRepository).save(soloArtist);
    }

    @Test
    void handleKeepsBirthYearNullForGroupTypeArtist() {
        PersonEntity band = PersonEntity.builder().id(personId).name("Artist").build();
        when(personRepository.findById(personId)).thenReturn(Optional.of(band));
        when(metadataRepository.findByPersonEntityId(personId)).thenReturn(List.of());
        when(imageRepository.findByPersonEntityId(personId)).thenReturn(List.of());
        when(musicBrainzService.getArtistInfo("Artist"))
                .thenReturn(Optional.of(new MusicBrainzService.ArtistInfo("A bio", "rock", null, "Group", "1985")));

        subject.handle(data);

        assertEquals(null, band.getBirthYear());
        verify(personRepository, never()).save(any());
    }

    @Test
    void handleSkipsWhenMetadataAndImageAlreadyExist() {
        when(personRepository.findById(personId)).thenReturn(Optional.of(artist));
        when(metadataRepository.findByPersonEntityId(personId))
                .thenReturn(List.of(MetadataEntity.builder().build()));
        when(imageRepository.findByPersonEntityId(personId))
                .thenReturn(List.of(ImageEntity.builder().build()));

        subject.handle(data);

        verifyNoInteractions(musicBrainzService, imageDownloadService);
        verify(metadataRepository, never()).save(any());
    }

    @Test
    void handleSwallowsImageDownloadFailureAndStillSavesMetadata() throws IOException {
        when(personRepository.findById(personId)).thenReturn(Optional.of(artist));
        when(metadataRepository.findByPersonEntityId(personId)).thenReturn(List.of());
        when(imageRepository.findByPersonEntityId(personId)).thenReturn(List.of());
        when(musicBrainzService.getArtistInfo("Artist"))
                .thenReturn(Optional.of(new MusicBrainzService.ArtistInfo("A bio", "rock", IMAGE_URL, null, null)));
        doThrow(new IOException("download failed"))
                .when(imageDownloadService).downloadAndSave(anyString(), any(), anyString(), anyString(), any());

        assertDoesNotThrow(() -> subject.handle(data));

        verify(metadataRepository).save(any(MetadataEntity.class));
    }

    @Test
    void handleSavesOnlyMetadataWhenNoImageUrl() {
        when(personRepository.findById(personId)).thenReturn(Optional.of(artist));
        when(metadataRepository.findByPersonEntityId(personId)).thenReturn(List.of());
        when(imageRepository.findByPersonEntityId(personId)).thenReturn(List.of());
        when(musicBrainzService.getArtistInfo("Artist"))
                .thenReturn(Optional.of(new MusicBrainzService.ArtistInfo("A bio", "rock", null, null, null)));

        subject.handle(data);

        verify(metadataRepository).save(any(MetadataEntity.class));
        verifyNoInteractions(imageDownloadService);
    }

    @Test
    void handleDownloadsOnlyImageWhenMetadataAlreadyExists() throws IOException {
        when(personRepository.findById(personId)).thenReturn(Optional.of(artist));
        when(metadataRepository.findByPersonEntityId(personId))
                .thenReturn(List.of(MetadataEntity.builder().build()));
        when(imageRepository.findByPersonEntityId(personId)).thenReturn(List.of());
        when(musicBrainzService.getArtistInfo("Artist"))
                .thenReturn(Optional.of(new MusicBrainzService.ArtistInfo("A bio", "rock", IMAGE_URL, null, null)));

        subject.handle(data);

        verify(metadataRepository, never()).save(any());
        verify(imageDownloadService).downloadAndSave(
                IMAGE_URL, ImageType.COVER, "eng",
                "wikipedia://" + IMAGE_URL,
                new ImageSave.MediaEntityRef(null, null, null, artist, null));
    }

    @Test
    void handleDoesNotSaveMetadataWhenBioIsNull() throws IOException {
        when(personRepository.findById(personId)).thenReturn(Optional.of(artist));
        when(metadataRepository.findByPersonEntityId(personId)).thenReturn(List.of());
        when(imageRepository.findByPersonEntityId(personId)).thenReturn(List.of());
        when(musicBrainzService.getArtistInfo("Artist"))
                .thenReturn(Optional.of(new MusicBrainzService.ArtistInfo(null, "rock", IMAGE_URL, null, null)));

        subject.handle(data);

        verify(metadataRepository, never()).save(any());
        verify(imageDownloadService).downloadAndSave(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void handleDoesNothingWhenNoArtistInfoFound() {
        when(personRepository.findById(personId)).thenReturn(Optional.of(artist));
        when(metadataRepository.findByPersonEntityId(personId)).thenReturn(List.of());
        when(imageRepository.findByPersonEntityId(personId)).thenReturn(List.of());
        when(musicBrainzService.getArtistInfo("Artist")).thenReturn(Optional.empty());

        subject.handle(data);

        verify(metadataRepository, never()).save(any());
        verifyNoInteractions(imageDownloadService);
    }
}
