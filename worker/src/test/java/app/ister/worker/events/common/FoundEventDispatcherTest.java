package app.ister.worker.events.common;

import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.EventType;
import app.ister.core.eventdata.AlbumFoundData;
import app.ister.core.eventdata.AudioFileFoundData;
import app.ister.core.eventdata.BookFoundData;
import app.ister.core.eventdata.ComicFileFoundData;
import app.ister.core.eventdata.ComicSeriesFoundData;
import app.ister.core.eventdata.EpisodeFoundData;
import app.ister.core.eventdata.EpubFileFoundData;
import app.ister.core.eventdata.MovieFoundData;
import app.ister.core.eventdata.NfoFileFoundData;
import app.ister.core.eventdata.PersonFoundData;
import app.ister.core.eventdata.ShowFoundData;
import app.ister.core.service.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class FoundEventDispatcherTest {

    @InjectMocks
    private FoundEventDispatcher subject;

    @Mock
    private MessageSender messageSender;

    private final DirectoryEntity dir = DirectoryEntity.builder().id(UUID.randomUUID()).name("books-1").build();

    @Test
    void showFoundSendsGlobalShowEvent() {
        UUID showId = UUID.randomUUID();

        subject.showFound(showId);

        ArgumentCaptor<ShowFoundData> captor = ArgumentCaptor.forClass(ShowFoundData.class);
        verify(messageSender).sendShowFound(captor.capture());
        assertEquals(EventType.SHOW_FOUND, captor.getValue().getEventType());
        assertEquals(showId, captor.getValue().getShowId());
        verifyNoMoreInteractions(messageSender);
    }

    @Test
    void episodeFoundSendsGlobalEpisodeEvent() {
        UUID episodeId = UUID.randomUUID();

        subject.episodeFound(episodeId);

        ArgumentCaptor<EpisodeFoundData> captor = ArgumentCaptor.forClass(EpisodeFoundData.class);
        verify(messageSender).sendEpisodeFound(captor.capture());
        assertEquals(EventType.EPISODE_FOUND, captor.getValue().getEventType());
        assertEquals(episodeId, captor.getValue().getEpisodeId());
        verifyNoMoreInteractions(messageSender);
    }

    @Test
    void movieFoundSendsGlobalMovieEvent() {
        UUID movieId = UUID.randomUUID();

        subject.movieFound(movieId);

        ArgumentCaptor<MovieFoundData> captor = ArgumentCaptor.forClass(MovieFoundData.class);
        verify(messageSender).sendMovieFound(captor.capture());
        assertEquals(EventType.MOVIE_FOUND, captor.getValue().getEventType());
        assertEquals(movieId, captor.getValue().getMovieId());
        verifyNoMoreInteractions(messageSender);
    }

    @Test
    void personFoundGlobalUsesTheUnscopedQueue() {
        UUID personId = UUID.randomUUID();

        subject.personFoundGlobal(personId);

        ArgumentCaptor<PersonFoundData> captor = ArgumentCaptor.forClass(PersonFoundData.class);
        verify(messageSender).sendPersonFound(captor.capture());
        verify(messageSender, never()).sendPersonFound(any(), anyString());
        assertEquals(EventType.PERSON_FOUND, captor.getValue().getEventType());
        assertEquals(personId, captor.getValue().getPersonId());
    }

    @Test
    void personFoundToNodeRoutesToThatNode() {
        UUID personId = UUID.randomUUID();

        subject.personFoundToNode(personId, "node-b");

        ArgumentCaptor<PersonFoundData> captor = ArgumentCaptor.forClass(PersonFoundData.class);
        verify(messageSender).sendPersonFound(captor.capture(), eq("node-b"));
        verify(messageSender, never()).sendPersonFound(any());
        assertEquals(EventType.PERSON_FOUND, captor.getValue().getEventType());
        assertEquals(personId, captor.getValue().getPersonId());
    }

    @Test
    void albumFoundGlobalUsesTheUnscopedQueue() {
        UUID albumId = UUID.randomUUID();

        subject.albumFoundGlobal(albumId);

        ArgumentCaptor<AlbumFoundData> captor = ArgumentCaptor.forClass(AlbumFoundData.class);
        verify(messageSender).sendAlbumFound(captor.capture());
        verify(messageSender, never()).sendAlbumFound(any(), anyString());
        assertEquals(EventType.ALBUM_FOUND, captor.getValue().getEventType());
        assertEquals(albumId, captor.getValue().getAlbumId());
    }

    @Test
    void albumFoundToNodeRoutesToThatNode() {
        UUID albumId = UUID.randomUUID();

        subject.albumFoundToNode(albumId, "node-c");

        ArgumentCaptor<AlbumFoundData> captor = ArgumentCaptor.forClass(AlbumFoundData.class);
        verify(messageSender).sendAlbumFound(captor.capture(), eq("node-c"));
        verify(messageSender, never()).sendAlbumFound(any());
        assertEquals(EventType.ALBUM_FOUND, captor.getValue().getEventType());
        assertEquals(albumId, captor.getValue().getAlbumId());
    }

    @Test
    void bookFoundSendsGlobalBookEvent() {
        UUID bookId = UUID.randomUUID();

        subject.bookFound(bookId);

        ArgumentCaptor<BookFoundData> captor = ArgumentCaptor.forClass(BookFoundData.class);
        verify(messageSender).sendBookFound(captor.capture());
        assertEquals(EventType.BOOK_FOUND, captor.getValue().getEventType());
        assertEquals(bookId, captor.getValue().getBookId());
        verifyNoMoreInteractions(messageSender);
    }

    @Test
    void comicSeriesFoundSendsGlobalSeriesEvent() {
        UUID seriesId = UUID.randomUUID();

        subject.comicSeriesFound(seriesId);

        ArgumentCaptor<ComicSeriesFoundData> captor = ArgumentCaptor.forClass(ComicSeriesFoundData.class);
        verify(messageSender).sendComicSeriesFound(captor.capture());
        assertEquals(EventType.COMIC_SERIES_FOUND, captor.getValue().getEventType());
        assertEquals(seriesId, captor.getValue().getSeriesId());
        verifyNoMoreInteractions(messageSender);
    }

    @Test
    void audioFileFoundCarriesTrackAndRoutesToTheDirectory() {
        TrackEntity track = TrackEntity.builder().id(UUID.randomUUID()).build();
        MediaFileEntity mediaFile = MediaFileEntity.builder()
                .id(UUID.randomUUID())
                .directoryEntityId(dir.getId())
                .path("/music/Artist/Album/01.flac")
                .trackEntity(track)
                .build();

        subject.audioFileFound(mediaFile, "music-1");

        ArgumentCaptor<AudioFileFoundData> captor = ArgumentCaptor.forClass(AudioFileFoundData.class);
        verify(messageSender).sendAudioFileFound(captor.capture(), eq("music-1"));
        AudioFileFoundData data = captor.getValue();
        assertEquals(EventType.AUDIO_FILE_FOUND, data.getEventType());
        assertEquals(dir.getId(), data.getDirectoryEntityUUID());
        assertEquals(track.getId(), data.getTrackEntityUUID());
        assertNull(data.getChapterEntityUUID());
        assertEquals("/music/Artist/Album/01.flac", data.getPath());
    }

    @Test
    void audioFileFoundCarriesChapterForAudiobooks() {
        ChapterEntity chapter = ChapterEntity.builder().id(UUID.randomUUID()).build();
        MediaFileEntity mediaFile = MediaFileEntity.builder()
                .id(UUID.randomUUID())
                .directoryEntityId(dir.getId())
                .path("/books/Author/Book/001_Chapter.mp3")
                .chapterEntity(chapter)
                .build();

        subject.audioFileFound(mediaFile, dir.getName());

        ArgumentCaptor<AudioFileFoundData> captor = ArgumentCaptor.forClass(AudioFileFoundData.class);
        verify(messageSender).sendAudioFileFound(captor.capture(), eq(dir.getName()));
        assertEquals(chapter.getId(), captor.getValue().getChapterEntityUUID());
        assertNull(captor.getValue().getTrackEntityUUID());
    }

    @Test
    void epubFileFoundIsDirectoryScoped() {
        UUID bookId = UUID.randomUUID();
        MediaFileEntity epub = MediaFileEntity.builder().id(UUID.randomUUID()).path("/books/Author/Book.epub").build();

        subject.epubFileFound(dir, bookId, epub);

        ArgumentCaptor<EpubFileFoundData> captor = ArgumentCaptor.forClass(EpubFileFoundData.class);
        verify(messageSender).sendEpubFileFound(captor.capture(), eq("books-1"));
        EpubFileFoundData data = captor.getValue();
        assertEquals(EventType.EPUB_FILE_FOUND, data.getEventType());
        assertEquals(dir.getId(), data.getDirectoryEntityUUID());
        assertEquals(bookId, data.getBookEntityUUID());
        assertEquals(epub.getId(), data.getMediaFileEntityUUID());
        assertEquals("/books/Author/Book.epub", data.getPath());
        verifyNoMoreInteractions(messageSender);
    }

    @Test
    void comicFileFoundIsDirectoryScoped() {
        UUID volumeId = UUID.randomUUID();
        MediaFileEntity cbz = MediaFileEntity.builder().id(UUID.randomUUID()).path("/comics/Series/Vol 1.cbz").build();

        subject.comicFileFound(dir, volumeId, cbz);

        ArgumentCaptor<ComicFileFoundData> captor = ArgumentCaptor.forClass(ComicFileFoundData.class);
        verify(messageSender).sendComicFileFound(captor.capture(), eq("books-1"));
        ComicFileFoundData data = captor.getValue();
        assertEquals(EventType.COMIC_FILE_FOUND, data.getEventType());
        assertEquals(dir.getId(), data.getDirectoryEntityUUID());
        assertEquals(volumeId, data.getBookEntityUUID());
        assertEquals(cbz.getId(), data.getMediaFileEntityUUID());
        assertEquals("/comics/Series/Vol 1.cbz", data.getPath());
        verifyNoMoreInteractions(messageSender);
    }

    @Test
    void comicVolumeFileFoundRoutesEpubsToTheEpubParserCaseInsensitively() {
        UUID volumeId = UUID.randomUUID();
        MediaFileEntity epub = MediaFileEntity.builder().id(UUID.randomUUID()).path("/comics/Series/Vol 1.EPUB").build();

        subject.comicVolumeFileFound(dir, volumeId, epub);

        ArgumentCaptor<EpubFileFoundData> captor = ArgumentCaptor.forClass(EpubFileFoundData.class);
        verify(messageSender).sendEpubFileFound(captor.capture(), eq("books-1"));
        verify(messageSender, never()).sendComicFileFound(any(), anyString());
        assertEquals(volumeId, captor.getValue().getBookEntityUUID());
        assertEquals(epub.getId(), captor.getValue().getMediaFileEntityUUID());
    }

    @Test
    void comicVolumeFileFoundRoutesArchivesAndPdfsToTheComicParser() {
        UUID volumeId = UUID.randomUUID();
        MediaFileEntity pdf = MediaFileEntity.builder().id(UUID.randomUUID()).path("/comics/Series/Vol 2.pdf").build();

        subject.comicVolumeFileFound(dir, volumeId, pdf);

        ArgumentCaptor<ComicFileFoundData> captor = ArgumentCaptor.forClass(ComicFileFoundData.class);
        verify(messageSender).sendComicFileFound(captor.capture(), eq("books-1"));
        verify(messageSender, never()).sendEpubFileFound(any(), anyString());
        assertEquals(volumeId, captor.getValue().getBookEntityUUID());
        assertEquals("/comics/Series/Vol 2.pdf", captor.getValue().getPath());
    }

    @Test
    void nfoFileFoundIsDirectoryScoped() {
        subject.nfoFileFound(dir, "/music/Artist/artist.nfo");

        ArgumentCaptor<NfoFileFoundData> captor = ArgumentCaptor.forClass(NfoFileFoundData.class);
        verify(messageSender).sendNfoFileFound(captor.capture(), eq("books-1"));
        assertEquals(EventType.NFO_FILE_FOUND, captor.getValue().getEventType());
        assertEquals(dir.getId(), captor.getValue().getDirectoryEntityUUID());
        assertEquals("/music/Artist/artist.nfo", captor.getValue().getPath());
        verifyNoMoreInteractions(messageSender);
    }
}
