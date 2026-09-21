package app.ister.disk.upload;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileEpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.AudioFileFoundData;
import app.ister.core.eventdata.ComicFileFoundData;
import app.ister.core.eventdata.EpubFileFoundData;
import app.ister.core.eventdata.FileScanRequestedData;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.eventdata.MediaFileFoundData;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileEpisodeRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.storage.LocalCopy;
import app.ister.core.storage.TmpStore;
import app.ister.core.storage.TmpStoreProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadedFilePublisherTest {

    @TempDir
    Path tmpDir;

    private final MessageSender messageSender = mock(MessageSender.class);
    private final MediaFileRepository mediaFileRepository = mock(MediaFileRepository.class);
    private final MediaFileEpisodeRepository mediaFileEpisodeRepository = mock(MediaFileEpisodeRepository.class);
    private final ImageRepository imageRepository = mock(ImageRepository.class);
    private final LocalCopy localCopy = mock(LocalCopy.class);
    private final TmpStoreProvider tmpStoreProvider = mock(TmpStoreProvider.class);
    private final TmpStore sharedTmp = mock(TmpStore.class);
    private UploadedFilePublisher publisher;

    @BeforeEach
    void setUp() {
        when(tmpStoreProvider.shared()).thenReturn(Optional.empty());
        publisher = new UploadedFilePublisher(messageSender, mediaFileRepository, mediaFileEpisodeRepository,
                imageRepository, localCopy, tmpStoreProvider, tmpDir.toString());
    }

    private static DirectoryEntity directory(LibraryType type) {
        DirectoryEntity directory = DirectoryEntity.builder().name("disk").path("/media")
                .directoryType(DirectoryType.LIBRARY)
                .libraryEntity(LibraryEntity.builder().name(type.name()).libraryType(type).build()).build();
        directory.setId(UUID.randomUUID());
        return directory;
    }

    private MediaFileEntity existing(DirectoryEntity directory, MediaFileEntity mediaFile) {
        mediaFile.setId(UUID.randomUUID());
        when(mediaFileRepository.findByDirectoryEntityAndPath(directory, mediaFile.getPath()))
                .thenReturn(Optional.of(mediaFile));
        return mediaFile;
    }

    @Test
    void aNewFileIsOneFileScanRequest() {
        DirectoryEntity directory = directory(LibraryType.MOVIE);
        when(mediaFileRepository.findByDirectoryEntityAndPath(any(), any())).thenReturn(Optional.empty());
        when(imageRepository.findByDirectoryEntityAndPath(any(), any())).thenReturn(Optional.empty());

        publisher.published(directory, "/media/Film (2019)/film.mkv", 42L);

        ArgumentCaptor<FileScanRequestedData> data = ArgumentCaptor.forClass(FileScanRequestedData.class);
        verify(messageSender).sendFileScanRequested(data.capture(), eq("disk"));
        assertThat(data.getValue().getPath()).isEqualTo("/media/Film (2019)/film.mkv");
        assertThat(data.getValue().getSize()).isEqualTo(42L);
        assertThat(data.getValue().getDirectoryEntityUUID()).isEqualTo(directory.getId());
    }

    @Test
    void aReplacedImageIsProcessedAgain() {
        DirectoryEntity directory = directory(LibraryType.MOVIE);
        ImageEntity image = ImageEntity.builder().directoryEntity(directory).path("/media/Film (2019)/cover.jpg").build();
        when(mediaFileRepository.findByDirectoryEntityAndPath(any(), any())).thenReturn(Optional.empty());
        when(imageRepository.findByDirectoryEntityAndPath(directory, image.getPath())).thenReturn(Optional.of(image));

        publisher.published(directory, image.getPath(), 10L);

        ArgumentCaptor<ImageFoundData> data = ArgumentCaptor.forClass(ImageFoundData.class);
        verify(messageSender).sendImageFound(data.capture(), eq("disk"));
        assertThat(data.getValue().getPath()).isEqualTo(image.getPath());
    }

    @Test
    void aReplacedMovieDropsItsTranscodeAndIsAnalyzedAgain() throws IOException {
        DirectoryEntity directory = directory(LibraryType.MOVIE);
        MovieEntity movie = MovieEntity.builder().build();
        movie.setId(UUID.randomUUID());
        MediaFileEntity mediaFile = existing(directory,
                MediaFileEntity.builder().path("/media/Film (2019)/film.mkv").movieEntity(movie).size(1L).build());
        when(mediaFileEpisodeRepository.findByMediaFileEntityIdOrderByPartNumber(mediaFile.getId())).thenReturn(List.of());
        when(tmpStoreProvider.shared()).thenReturn(Optional.of(sharedTmp));
        Path transcode = Files.createDirectories(tmpDir.resolve(mediaFile.getId().toString()).resolve("video"));
        Files.writeString(transcode.resolve("0.ts"), "old");

        publisher.published(directory, mediaFile.getPath(), 99L);

        assertThat(mediaFile.getSize()).isEqualTo(99L);
        verify(mediaFileRepository).save(mediaFile);
        verify(localCopy).evict(mediaFile.getPath());
        verify(sharedTmp).deleteAll(mediaFile.getId());
        assertThat(tmpDir.resolve(mediaFile.getId().toString())).doesNotExist();
        ArgumentCaptor<MediaFileFoundData> data = ArgumentCaptor.forClass(MediaFileFoundData.class);
        verify(messageSender).sendMediaFileFound(data.capture(), eq("disk"));
        assertThat(data.getValue().getMovieEntityUUID()).isEqualTo(movie.getId());
        assertThat(data.getValue().getEpisodeEntityUUID()).isNull();
        assertThat(data.getValue().getEpisodeEntityUUIDs()).isNull();
    }

    @Test
    void aReplacedMultiEpisodeFileKeepsAllItsEpisodes() throws IOException {
        DirectoryEntity directory = directory(LibraryType.SHOW);
        EpisodeEntity episode = EpisodeEntity.builder().build();
        episode.setId(UUID.randomUUID());
        UUID second = UUID.randomUUID();
        MediaFileEntity mediaFile = existing(directory,
                MediaFileEntity.builder().path("/media/Show/s01e01-e02.mkv").episodeEntity(episode).build());
        when(mediaFileEpisodeRepository.findByMediaFileEntityIdOrderByPartNumber(mediaFile.getId())).thenReturn(List.of(
                MediaFileEpisodeEntity.builder().episodeEntityId(episode.getId()).build(),
                MediaFileEpisodeEntity.builder().episodeEntityId(second).build()));
        // a failing shared tmp must not stop the re-analysis
        when(tmpStoreProvider.shared()).thenReturn(Optional.of(sharedTmp));
        doThrow(new IOException("bucket down")).when(sharedTmp).deleteAll(mediaFile.getId());

        publisher.published(directory, mediaFile.getPath(), 5L);

        ArgumentCaptor<MediaFileFoundData> data = ArgumentCaptor.forClass(MediaFileFoundData.class);
        verify(messageSender).sendMediaFileFound(data.capture(), eq("disk"));
        assertThat(data.getValue().getEpisodeEntityUUID()).isEqualTo(episode.getId());
        assertThat(data.getValue().getEpisodeEntityUUIDs()).containsExactly(episode.getId(), second);
    }

    @Test
    void aReplacedTrackGoesToTheAudioPipeline() {
        DirectoryEntity directory = directory(LibraryType.MUSIC);
        TrackEntity track = TrackEntity.builder().build();
        track.setId(UUID.randomUUID());
        MediaFileEntity mediaFile = existing(directory,
                MediaFileEntity.builder().path("/media/Artist/Album/01.flac").trackEntity(track).build());

        publisher.published(directory, mediaFile.getPath(), 5L);

        ArgumentCaptor<AudioFileFoundData> data = ArgumentCaptor.forClass(AudioFileFoundData.class);
        verify(messageSender).sendAudioFileFound(data.capture(), eq("disk"));
        assertThat(data.getValue().getTrackEntityUUID()).isEqualTo(track.getId());
    }

    @Test
    void aReplacedBookFileIsAnEpubOrAComicArchive() {
        BookEntity book = BookEntity.builder().build();
        book.setId(UUID.randomUUID());
        DirectoryEntity books = directory(LibraryType.BOOK);
        MediaFileEntity epub = existing(books,
                MediaFileEntity.builder().path("/media/Owl/Night Flight.epub").bookEntity(book).build());
        DirectoryEntity comics = directory(LibraryType.COMIC);
        MediaFileEntity archive = existing(comics,
                MediaFileEntity.builder().path("/media/Series/Issue 01.cbz").bookEntity(book).build());
        MediaFileEntity comicEpub = existing(comics,
                MediaFileEntity.builder().path("/media/Series/Issue 02.EPUB").bookEntity(book).build());

        publisher.published(books, epub.getPath(), 5L);
        publisher.published(comics, archive.getPath(), 5L);
        publisher.published(comics, comicEpub.getPath(), 5L);

        ArgumentCaptor<EpubFileFoundData> epubs = ArgumentCaptor.forClass(EpubFileFoundData.class);
        verify(messageSender, org.mockito.Mockito.times(2)).sendEpubFileFound(epubs.capture(), eq("disk"));
        assertThat(epubs.getAllValues()).extracting(EpubFileFoundData::getPath)
                .containsExactly(epub.getPath(), comicEpub.getPath());
        ArgumentCaptor<ComicFileFoundData> comic = ArgumentCaptor.forClass(ComicFileFoundData.class);
        verify(messageSender).sendComicFileFound(comic.capture(), eq("disk"));
        assertThat(comic.getValue().getEventType()).isEqualTo(EventType.COMIC_FILE_FOUND);
        assertThat(comic.getValue().getBookEntityUUID()).isEqualTo(book.getId());
    }
}
