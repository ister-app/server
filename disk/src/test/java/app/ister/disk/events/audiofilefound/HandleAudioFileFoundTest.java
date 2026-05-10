package app.ister.disk.events.audiofilefound;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.ArtistEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.AudioFileFoundData;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.ArtistRepository;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.MediaFileStreamRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import app.ister.core.utils.Jaffree;
import app.ister.disk.events.mediafilefound.MediaFileFoundCheckForStreams;
import app.ister.disk.events.mediafilefound.MediaFileFoundGetDuration;
import com.github.kokorin.jaffree.ffprobe.FFprobe;
import com.github.kokorin.jaffree.ffprobe.FFprobeResult;
import com.github.kokorin.jaffree.ffprobe.Format;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_SELF;

@ExtendWith(MockitoExtension.class)
class HandleAudioFileFoundTest {

    @Mock
    private DirectoryRepository directoryRepositoryMock;
    @Mock
    private MediaFileRepository mediaFileRepositoryMock;
    @Mock
    private MediaFileStreamRepository mediaFileStreamRepositoryMock;
    @Mock
    private MetadataRepository metadataRepositoryMock;
    @Mock
    private TrackRepository trackRepositoryMock;
    @Mock
    private ArtistRepository artistRepositoryMock;
    @Mock
    private AlbumRepository albumRepositoryMock;
    @Mock
    private MediaFileFoundGetDuration mediaFileFoundGetDurationMock;
    @Mock
    private MediaFileFoundCheckForStreams mediaFileFoundCheckForStreamsMock;
    @Mock
    private Jaffree jaffreeMock;
    @Mock
    private ScannerHelperService scannerHelperServiceMock;
    @Mock
    private ImageRepository imageRepositoryMock;
    @Mock
    private AudioFileFoundExtractCoverArt audioFileFoundExtractCoverArtMock;
    @Mock
    private MessageSender messageSenderMock;

    @TempDir
    Path tempDir;

    private HandleAudioFileFound subject;

    private static final UUID DIRECTORY_ID = UUID.randomUUID();
    private static final UUID TRACK_ID = UUID.randomUUID();
    private static final String PATH = "/music/Artist/Album/01 - Track.flac";

    @BeforeEach
    void setup() {
        subject = new HandleAudioFileFound(
                directoryRepositoryMock,
                mediaFileRepositoryMock,
                mediaFileStreamRepositoryMock,
                metadataRepositoryMock,
                trackRepositoryMock,
                artistRepositoryMock,
                albumRepositoryMock,
                imageRepositoryMock,
                scannerHelperServiceMock,
                mediaFileFoundGetDurationMock,
                mediaFileFoundCheckForStreamsMock,
                audioFileFoundExtractCoverArtMock,
                messageSenderMock,
                jaffreeMock);
        ReflectionTestUtils.setField(subject, "dirOfFFmpeg", "/usr/bin");
        ReflectionTestUtils.setField(subject, "tmpDir", tempDir.toString());
    }

    @Test
    void handlesReturnsCorrectEventType() {
        org.junit.jupiter.api.Assertions.assertEquals(EventType.AUDIO_FILE_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        AudioFileFoundData data = AudioFileFoundData.builder()
                .eventType(EventType.MEDIA_FILE_FOUND)
                .build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(data));
    }

    @Test
    void handleAnalyzesMediaFile() {
        DirectoryEntity directory = DirectoryEntity.builder().build();
        ReflectionTestUtils.setField(directory, "id", DIRECTORY_ID);

        UUID mediaFileId = UUID.randomUUID();
        MediaFileEntity mediaFile = MediaFileEntity.builder()
                .path(PATH)
                .size(1000L).build();
        ReflectionTestUtils.setField(mediaFile, "id", mediaFileId);

        when(directoryRepositoryMock.findById(DIRECTORY_ID)).thenReturn(Optional.of(directory));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPathForUpdate(directory, PATH)).thenReturn(Optional.of(mediaFile));
        when(mediaFileRepositoryMock.findById(mediaFileId)).thenReturn(Optional.of(mediaFile));
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(any(), any())).thenReturn(new MediaFileFoundCheckForStreams.CheckResult(List.of(), false, 180000L));

        var data = AudioFileFoundData.builder()
                .eventType(EventType.AUDIO_FILE_FOUND)
                .directoryEntityUUID(DIRECTORY_ID)
                .trackEntityUUID(TRACK_ID)
                .path(PATH).build();

        assertTrue(subject.handle(data));

        verify(mediaFileRepositoryMock).save(mediaFile);
        verify(mediaFileStreamRepositoryMock).deleteAllByMediaFileEntityId(any());
        verify(mediaFileFoundCheckForStreamsMock).checkForStreams(eq(mediaFile), any());
    }

    @Test
    void handleDoesNothingWhenMediaFileNotFound() {
        DirectoryEntity directory = DirectoryEntity.builder().build();
        ReflectionTestUtils.setField(directory, "id", DIRECTORY_ID);

        when(directoryRepositoryMock.findById(DIRECTORY_ID)).thenReturn(Optional.of(directory));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPathForUpdate(directory, PATH)).thenReturn(Optional.empty());

        var data = AudioFileFoundData.builder()
                .eventType(EventType.AUDIO_FILE_FOUND)
                .directoryEntityUUID(DIRECTORY_ID)
                .trackEntityUUID(TRACK_ID)
                .path(PATH).build();

        assertTrue(subject.handle(data));

        verifyNoInteractions(mediaFileFoundGetDurationMock);
        verifyNoInteractions(mediaFileFoundCheckForStreamsMock);
    }

    @Test
    void handleSavesMetadataFromTagsWhenTrackFound() {
        DirectoryEntity directory = DirectoryEntity.builder().build();
        ReflectionTestUtils.setField(directory, "id", DIRECTORY_ID);

        UUID mediaFileId = UUID.randomUUID();
        MediaFileEntity mediaFile = MediaFileEntity.builder()
                .path(PATH)
                .size(1000L).build();
        ReflectionTestUtils.setField(mediaFile, "id", mediaFileId);

        LibraryEntity library = LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music").build();
        ArtistEntity artist = ArtistEntity.builder().libraryEntity(library).name("Artist").build();
        AlbumEntity album = AlbumEntity.builder().libraryEntity(library).artistEntity(artist).name("Album").releaseYear(2024).build();
        TrackEntity track = TrackEntity.builder().artistEntity(artist).albumEntity(album).number(1).discNumber(1)
                .metadataEntities(new ArrayList<>()).build();

        FFprobe ffprobe = mock(FFprobe.class, RETURNS_SELF);
        FFprobeResult result = mock(FFprobeResult.class);
        Format format = mock(Format.class);

        when(jaffreeMock.getFFPROBE()).thenReturn(ffprobe);
        when(ffprobe.execute()).thenReturn(result);
        when(result.getFormat()).thenReturn(format);
        when(format.getTag("track")).thenReturn(null);
        when(format.getTag("TRACK")).thenReturn(null);
        when(format.getTag("tracknumber")).thenReturn(null);
        when(format.getTag("TRACKNUMBER")).thenReturn(null);
        when(format.getTag("title")).thenReturn("My Track");
        when(format.getTag("album_artist")).thenReturn(null);
        when(format.getTag("ALBUM_ARTIST")).thenReturn(null);
        when(format.getTag("comment")).thenReturn("Nice song");
        when(format.getTag("album")).thenReturn(null);
        when(format.getTag("ALBUM")).thenReturn(null);
        when(format.getTag("date")).thenReturn(null);
        when(format.getTag("DATE")).thenReturn(null);
        when(format.getTag("year")).thenReturn(null);
        when(format.getTag("YEAR")).thenReturn(null);

        when(directoryRepositoryMock.findById(DIRECTORY_ID)).thenReturn(Optional.of(directory));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPathForUpdate(directory, PATH)).thenReturn(Optional.of(mediaFile));
        when(mediaFileRepositoryMock.findById(mediaFileId)).thenReturn(Optional.of(mediaFile));
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(any(), any())).thenReturn(new MediaFileFoundCheckForStreams.CheckResult(List.of(), false, 180000L));
        when(trackRepositoryMock.findById(TRACK_ID)).thenReturn(Optional.of(track));

        var data = AudioFileFoundData.builder()
                .eventType(EventType.AUDIO_FILE_FOUND)
                .directoryEntityUUID(DIRECTORY_ID)
                .trackEntityUUID(TRACK_ID)
                .path(PATH).build();

        assertTrue(subject.handle(data));

        verify(metadataRepositoryMock).deleteAll(any());
        verify(metadataRepositoryMock).save(any(MetadataEntity.class));
    }

    @Test
    void handleSkipsMetadataWhenTrackEntityUUIDIsNull() {
        DirectoryEntity directory = DirectoryEntity.builder().build();
        ReflectionTestUtils.setField(directory, "id", DIRECTORY_ID);

        UUID mediaFileId2 = UUID.randomUUID();
        MediaFileEntity mediaFile = MediaFileEntity.builder()
                .path(PATH)
                .size(1000L).build();
        ReflectionTestUtils.setField(mediaFile, "id", mediaFileId2);

        when(directoryRepositoryMock.findById(DIRECTORY_ID)).thenReturn(Optional.of(directory));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPathForUpdate(directory, PATH)).thenReturn(Optional.of(mediaFile));
        when(mediaFileRepositoryMock.findById(mediaFileId2)).thenReturn(Optional.of(mediaFile));
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(any(), any())).thenReturn(new MediaFileFoundCheckForStreams.CheckResult(List.of(), false, 0L));

        var data = AudioFileFoundData.builder()
                .eventType(EventType.AUDIO_FILE_FOUND)
                .directoryEntityUUID(DIRECTORY_ID)
                .trackEntityUUID(null)
                .path(PATH).build();

        assertTrue(subject.handle(data));

        verifyNoInteractions(trackRepositoryMock);
        verifyNoInteractions(metadataRepositoryMock);
    }

    @Test
    void handleUsesFilenameAsTitleWhenFormatHasNoTitleTag() {
        DirectoryEntity directory = DirectoryEntity.builder().build();
        ReflectionTestUtils.setField(directory, "id", DIRECTORY_ID);

        UUID mediaFileId = UUID.randomUUID();
        MediaFileEntity mediaFile = MediaFileEntity.builder()
                .path(PATH)
                .size(1000L).build();
        ReflectionTestUtils.setField(mediaFile, "id", mediaFileId);

        LibraryEntity library = LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music").build();
        ArtistEntity artist = ArtistEntity.builder().libraryEntity(library).name("Artist").build();
        AlbumEntity album = AlbumEntity.builder().libraryEntity(library).artistEntity(artist).name("Album").releaseYear(2024).build();
        TrackEntity track = TrackEntity.builder().artistEntity(artist).albumEntity(album).number(1).discNumber(1)
                .metadataEntities(new ArrayList<>()).build();

        FFprobe ffprobe = mock(FFprobe.class, RETURNS_SELF);
        FFprobeResult result = mock(FFprobeResult.class);
        Format format = mock(Format.class);

        when(jaffreeMock.getFFPROBE()).thenReturn(ffprobe);
        when(ffprobe.execute()).thenReturn(result);
        when(result.getFormat()).thenReturn(format);
        when(format.getTag("track")).thenReturn(null);
        when(format.getTag("TRACK")).thenReturn(null);
        when(format.getTag("tracknumber")).thenReturn(null);
        when(format.getTag("TRACKNUMBER")).thenReturn(null);
        when(format.getTag("title")).thenReturn(null);
        when(format.getTag("TITLE")).thenReturn(null);
        when(format.getTag("album_artist")).thenReturn(null);
        when(format.getTag("ALBUM_ARTIST")).thenReturn(null);
        when(format.getTag("comment")).thenReturn(null);
        when(format.getTag("album")).thenReturn(null);
        when(format.getTag("ALBUM")).thenReturn(null);
        when(format.getTag("date")).thenReturn(null);
        when(format.getTag("DATE")).thenReturn(null);
        when(format.getTag("year")).thenReturn(null);
        when(format.getTag("YEAR")).thenReturn(null);
        when(format.getTag("COMMENT")).thenReturn(null);

        when(directoryRepositoryMock.findById(DIRECTORY_ID)).thenReturn(Optional.of(directory));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPathForUpdate(directory, PATH)).thenReturn(Optional.of(mediaFile));
        when(mediaFileRepositoryMock.findById(mediaFileId)).thenReturn(Optional.of(mediaFile));
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(any(), any())).thenReturn(new MediaFileFoundCheckForStreams.CheckResult(List.of(), false, 180000L));
        when(trackRepositoryMock.findById(TRACK_ID)).thenReturn(Optional.of(track));

        var data = AudioFileFoundData.builder()
                .eventType(EventType.AUDIO_FILE_FOUND)
                .directoryEntityUUID(DIRECTORY_ID)
                .trackEntityUUID(TRACK_ID)
                .path(PATH).build();

        assertTrue(subject.handle(data));

        // Title derived from filename: "01 - Track" → "Track"
        verify(metadataRepositoryMock).save(any(MetadataEntity.class));
    }

    @Test
    void handleCorrectArtistNameFromTagWhenDifferentAndNotExists() {
        DirectoryEntity directory = DirectoryEntity.builder().build();
        ReflectionTestUtils.setField(directory, "id", DIRECTORY_ID);

        UUID mediaFileId = UUID.randomUUID();
        MediaFileEntity mediaFile = MediaFileEntity.builder()
                .path(PATH)
                .size(1000L).build();
        ReflectionTestUtils.setField(mediaFile, "id", mediaFileId);

        LibraryEntity library = LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music").build();
        ArtistEntity artist = ArtistEntity.builder().libraryEntity(library).name("OldArtistName").build();
        AlbumEntity album = AlbumEntity.builder().libraryEntity(library).artistEntity(artist).name("Album").releaseYear(2024).build();
        TrackEntity track = TrackEntity.builder().artistEntity(artist).albumEntity(album).number(1).discNumber(1)
                .metadataEntities(new ArrayList<>()).build();

        FFprobe ffprobe = mock(FFprobe.class, RETURNS_SELF);
        FFprobeResult result = mock(FFprobeResult.class);
        Format format = mock(Format.class);

        when(jaffreeMock.getFFPROBE()).thenReturn(ffprobe);
        when(ffprobe.execute()).thenReturn(result);
        when(result.getFormat()).thenReturn(format);
        when(format.getTag("track")).thenReturn(null);
        when(format.getTag("TRACK")).thenReturn(null);
        when(format.getTag("tracknumber")).thenReturn(null);
        when(format.getTag("TRACKNUMBER")).thenReturn(null);
        when(format.getTag("album_artist")).thenReturn("NewArtistName");
        when(format.getTag("title")).thenReturn("Track Title");
        when(format.getTag("comment")).thenReturn(null);
        when(format.getTag("COMMENT")).thenReturn(null);
        when(format.getTag("album")).thenReturn(null);
        when(format.getTag("ALBUM")).thenReturn(null);
        when(format.getTag("date")).thenReturn(null);
        when(format.getTag("DATE")).thenReturn(null);
        when(format.getTag("year")).thenReturn(null);
        when(format.getTag("YEAR")).thenReturn(null);
        when(artistRepositoryMock.findByLibraryEntityAndName(library, "NewArtistName")).thenReturn(Optional.empty());

        when(directoryRepositoryMock.findById(DIRECTORY_ID)).thenReturn(Optional.of(directory));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPathForUpdate(directory, PATH)).thenReturn(Optional.of(mediaFile));
        when(mediaFileRepositoryMock.findById(mediaFileId)).thenReturn(Optional.of(mediaFile));
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(any(), any())).thenReturn(new MediaFileFoundCheckForStreams.CheckResult(List.of(), false, 180000L));
        when(trackRepositoryMock.findById(TRACK_ID)).thenReturn(Optional.of(track));

        var data = AudioFileFoundData.builder()
                .eventType(EventType.AUDIO_FILE_FOUND)
                .directoryEntityUUID(DIRECTORY_ID)
                .trackEntityUUID(TRACK_ID)
                .path(PATH).build();

        assertTrue(subject.handle(data));

        org.junit.jupiter.api.Assertions.assertEquals("NewArtistName", artist.getName());
    }

    @Test
    void handleDoesNotChangeArtistNameWhenAlreadyMatchesTag() {
        DirectoryEntity directory = DirectoryEntity.builder().build();
        ReflectionTestUtils.setField(directory, "id", DIRECTORY_ID);

        UUID mediaFileId = UUID.randomUUID();
        MediaFileEntity mediaFile = MediaFileEntity.builder()
                .path(PATH)
                .size(1000L).build();
        ReflectionTestUtils.setField(mediaFile, "id", mediaFileId);

        LibraryEntity library = LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music").build();
        ArtistEntity artist = ArtistEntity.builder().libraryEntity(library).name("SameArtist").build();
        AlbumEntity album = AlbumEntity.builder().libraryEntity(library).artistEntity(artist).name("Album").releaseYear(2024).build();
        TrackEntity track = TrackEntity.builder().artistEntity(artist).albumEntity(album).number(1).discNumber(1)
                .metadataEntities(new ArrayList<>()).build();

        FFprobe ffprobe = mock(FFprobe.class, RETURNS_SELF);
        FFprobeResult result = mock(FFprobeResult.class);
        Format format = mock(Format.class);

        when(jaffreeMock.getFFPROBE()).thenReturn(ffprobe);
        when(ffprobe.execute()).thenReturn(result);
        when(result.getFormat()).thenReturn(format);
        when(format.getTag("track")).thenReturn(null);
        when(format.getTag("TRACK")).thenReturn(null);
        when(format.getTag("tracknumber")).thenReturn(null);
        when(format.getTag("TRACKNUMBER")).thenReturn(null);
        when(format.getTag("album_artist")).thenReturn("SameArtist");
        when(format.getTag("title")).thenReturn("Track Title");
        when(format.getTag("comment")).thenReturn(null);
        when(format.getTag("COMMENT")).thenReturn(null);
        when(format.getTag("album")).thenReturn(null);
        when(format.getTag("ALBUM")).thenReturn(null);
        when(format.getTag("date")).thenReturn(null);
        when(format.getTag("DATE")).thenReturn(null);
        when(format.getTag("year")).thenReturn(null);
        when(format.getTag("YEAR")).thenReturn(null);

        when(directoryRepositoryMock.findById(DIRECTORY_ID)).thenReturn(Optional.of(directory));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPathForUpdate(directory, PATH)).thenReturn(Optional.of(mediaFile));
        when(mediaFileRepositoryMock.findById(mediaFileId)).thenReturn(Optional.of(mediaFile));
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(any(), any())).thenReturn(new MediaFileFoundCheckForStreams.CheckResult(List.of(), false, 180000L));
        when(trackRepositoryMock.findById(TRACK_ID)).thenReturn(Optional.of(track));

        var data = AudioFileFoundData.builder()
                .eventType(EventType.AUDIO_FILE_FOUND)
                .directoryEntityUUID(DIRECTORY_ID)
                .trackEntityUUID(TRACK_ID)
                .path(PATH).build();

        assertTrue(subject.handle(data));

        verify(artistRepositoryMock, never()).findByLibraryEntityAndName(any(), any());
        org.junit.jupiter.api.Assertions.assertEquals("SameArtist", artist.getName());
    }

    @Test
    void handleCorrectsTrackNumberFromTag() {
        DirectoryEntity directory = DirectoryEntity.builder().build();
        ReflectionTestUtils.setField(directory, "id", DIRECTORY_ID);

        UUID mediaFileId = UUID.randomUUID();
        UUID correctedTrackId = UUID.randomUUID();

        LibraryEntity library = LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("Music").build();
        ArtistEntity artist = ArtistEntity.builder().libraryEntity(library).name("Artist").build();
        AlbumEntity album = AlbumEntity.builder().libraryEntity(library).artistEntity(artist).name("Album").releaseYear(2024).build();

        TrackEntity wrongTrack = TrackEntity.builder().artistEntity(artist).albumEntity(album).number(0).discNumber(1)
                .metadataEntities(new ArrayList<>()).build();
        ReflectionTestUtils.setField(wrongTrack, "id", TRACK_ID);

        TrackEntity correctTrack = TrackEntity.builder().artistEntity(artist).albumEntity(album).number(1).discNumber(1)
                .metadataEntities(new ArrayList<>()).build();
        ReflectionTestUtils.setField(correctTrack, "id", correctedTrackId);

        MediaFileEntity mediaFile = MediaFileEntity.builder()
                .path(PATH)
                .size(1000L).build();
        ReflectionTestUtils.setField(mediaFile, "id", mediaFileId);

        FFprobe ffprobe = mock(FFprobe.class, RETURNS_SELF);
        FFprobeResult result = mock(FFprobeResult.class);
        Format format = mock(Format.class);

        when(jaffreeMock.getFFPROBE()).thenReturn(ffprobe);
        when(ffprobe.execute()).thenReturn(result);
        when(result.getFormat()).thenReturn(format);
        when(format.getTag("track")).thenReturn("1");
        when(format.getTag("disc")).thenReturn(null);
        when(format.getTag("DISC")).thenReturn(null);
        when(format.getTag("discnumber")).thenReturn(null);
        when(format.getTag("DISCNUMBER")).thenReturn(null);
        when(format.getTag("album_artist")).thenReturn(null);
        when(format.getTag("ALBUM_ARTIST")).thenReturn(null);
        when(format.getTag("title")).thenReturn("Bring out Your Dead");
        when(format.getTag("comment")).thenReturn(null);
        when(format.getTag("COMMENT")).thenReturn(null);
        when(format.getTag("album")).thenReturn(null);
        when(format.getTag("ALBUM")).thenReturn(null);
        when(format.getTag("date")).thenReturn(null);
        when(format.getTag("DATE")).thenReturn(null);
        when(format.getTag("year")).thenReturn(null);
        when(format.getTag("YEAR")).thenReturn(null);

        when(directoryRepositoryMock.findById(DIRECTORY_ID)).thenReturn(Optional.of(directory));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPathForUpdate(directory, PATH)).thenReturn(Optional.of(mediaFile));
        when(mediaFileRepositoryMock.findById(mediaFileId)).thenReturn(Optional.of(mediaFile));
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(any(), any())).thenReturn(new MediaFileFoundCheckForStreams.CheckResult(List.of(), false, 180000L));
        when(trackRepositoryMock.findById(TRACK_ID)).thenReturn(Optional.of(wrongTrack));
        when(scannerHelperServiceMock.getOrCreateTrack(artist, album, 1, 1)).thenReturn(correctTrack);
        when(trackRepositoryMock.findById(correctedTrackId)).thenReturn(Optional.of(correctTrack));

        var data = AudioFileFoundData.builder()
                .eventType(EventType.AUDIO_FILE_FOUND)
                .directoryEntityUUID(DIRECTORY_ID)
                .trackEntityUUID(TRACK_ID)
                .path(PATH).build();

        assertTrue(subject.handle(data));

        verify(scannerHelperServiceMock).getOrCreateTrack(artist, album, 1, 1);
        verify(mediaFileRepositoryMock, atLeastOnce()).save(any(MediaFileEntity.class));
        verify(metadataRepositoryMock).save(any(MetadataEntity.class));
    }

    @Test
    void handleDeletesHlsCacheWhenDirectoryExists() throws IOException {
        DirectoryEntity directory = DirectoryEntity.builder().build();
        ReflectionTestUtils.setField(directory, "id", DIRECTORY_ID);

        UUID mediaFileId = UUID.randomUUID();
        MediaFileEntity mediaFile = MediaFileEntity.builder()
                .path(PATH)
                .size(1000L).build();
        ReflectionTestUtils.setField(mediaFile, "id", mediaFileId);

        // Create a fake HLS cache directory
        Path hlsDir = tempDir.resolve(mediaFileId.toString());
        Files.createDirectories(hlsDir);
        Files.writeString(hlsDir.resolve("segment.ts"), "fake ts content");

        when(directoryRepositoryMock.findById(DIRECTORY_ID)).thenReturn(Optional.of(directory));
        when(mediaFileRepositoryMock.findByDirectoryEntityAndPathForUpdate(directory, PATH)).thenReturn(Optional.of(mediaFile));
        when(mediaFileRepositoryMock.findById(mediaFileId)).thenReturn(Optional.of(mediaFile));
        when(mediaFileFoundCheckForStreamsMock.checkForStreams(any(), any())).thenReturn(new MediaFileFoundCheckForStreams.CheckResult(List.of(), false, 0L));

        var data = AudioFileFoundData.builder()
                .eventType(EventType.AUDIO_FILE_FOUND)
                .directoryEntityUUID(DIRECTORY_ID)
                .trackEntityUUID(null)
                .path(PATH).build();

        assertTrue(subject.handle(data));

        // HLS cache dir should be deleted
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(hlsDir));
    }
}
