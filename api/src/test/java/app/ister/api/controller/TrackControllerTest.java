package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.repository.TrackRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrackControllerTest {

    @InjectMocks
    private TrackController subject;

    @Mock
    private TrackRepository trackRepository;

    @Test
    void trackByIdReturnsFromRepository() {
        UUID id = UUID.randomUUID();
        PersonEntity artist = PersonEntity.builder().name("The Beatles").build();
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        TrackEntity track = TrackEntity.builder().number(1).discNumber(1)
                .personEntity(artist).albumEntity(album).build();
        when(trackRepository.findById(id)).thenReturn(Optional.of(track));

        Optional<TrackEntity> result = subject.trackById(id);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getNumber());
        assertEquals(1, result.get().getDiscNumber());
    }

    @Test
    void trackByIdReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(trackRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(subject.trackById(id).isEmpty());
    }

    @Test
    void artistSchemaMappingReturnsArtist() {
        PersonEntity artist = PersonEntity.builder().name("The Beatles").build();
        TrackEntity track = TrackEntity.builder().personEntity(artist).build();

        assertEquals(artist, subject.artist(track));
    }

    @Test
    void albumSchemaMappingReturnsAlbum() {
        AlbumEntity album = AlbumEntity.builder().name("Abbey Road").releaseYear(1969).build();
        TrackEntity track = TrackEntity.builder().albumEntity(album).build();

        assertEquals(album, subject.album(track));
    }

    @Test
    void metadataSchemaMappingReturnsMetadata() {
        MetadataEntity meta = MetadataEntity.builder().title("Come Together").build();
        TrackEntity track = TrackEntity.builder().number(1).discNumber(1)
                .metadataEntities(List.of(meta)).build();

        List<MetadataEntity> result = subject.metadata(track);

        assertEquals(1, result.size());
    }

    @Test
    void mediaFileSchemaMappingReturnsMediaFiles() {
        MediaFileEntity file = MediaFileEntity.builder().path("/music/track.flac").size(1000L).build();
        TrackEntity track = TrackEntity.builder().number(1).discNumber(1)
                .mediaFileEntities(List.of(file)).build();

        List<MediaFileEntity> result = subject.mediaFile(track);

        assertEquals(1, result.size());
    }
}
