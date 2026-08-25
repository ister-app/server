package app.ister.worker.events.common;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Builds and sends the {@code *_FOUND} events the maintenance flows re-dispatch. One place for the
 * builder boilerplate shared by the force refresh ({@code ANALYZE_DATA}) and the metadata backfill
 * ({@code METADATA_BACKFILL_REQUESTED}) handlers — including the routing decisions (global vs
 * node-scoped vs directory-scoped) that are easy to get wrong per call site.
 */
@Component
@RequiredArgsConstructor
public class FoundEventDispatcher {
    private final MessageSender messageSender;

    public void showFound(UUID showId) {
        messageSender.sendShowFound(
                ShowFoundData.builder().eventType(EventType.SHOW_FOUND).showId(showId).build());
    }

    public void episodeFound(UUID episodeId) {
        messageSender.sendEpisodeFound(
                EpisodeFoundData.builder().eventType(EventType.EPISODE_FOUND).episodeId(episodeId).build());
    }

    public void movieFound(UUID movieId) {
        messageSender.sendMovieFound(
                MovieFoundData.builder().eventType(EventType.MOVIE_FOUND).movieId(movieId).build());
    }

    /** Global: reaches the worker's enrichment handler (MusicBrainz/Open Library/Wikipedia). */
    public void personFoundGlobal(UUID personId) {
        messageSender.sendPersonFound(
                PersonFoundData.builder().eventType(EventType.PERSON_FOUND).personId(personId).build());
    }

    /** Node-scoped: reaches the disk handler on that node (artist.nfo / folder artwork re-parse). */
    public void personFoundToNode(UUID personId, String nodeName) {
        messageSender.sendPersonFound(
                PersonFoundData.builder().eventType(EventType.PERSON_FOUND).personId(personId).build(),
                nodeName);
    }

    /** Global: reaches the worker's MusicBrainz/cover handler. */
    public void albumFoundGlobal(UUID albumId) {
        messageSender.sendAlbumFound(
                AlbumFoundData.builder().eventType(EventType.ALBUM_FOUND).albumId(albumId).build());
    }

    /** Node-scoped: reaches the disk handler on that node (album.nfo / folder artwork re-parse). */
    public void albumFoundToNode(UUID albumId, String nodeName) {
        messageSender.sendAlbumFound(
                AlbumFoundData.builder().eventType(EventType.ALBUM_FOUND).albumId(albumId).build(),
                nodeName);
    }

    public void bookFound(UUID bookId) {
        messageSender.sendBookFound(
                BookFoundData.builder().eventType(EventType.BOOK_FOUND).bookId(bookId).build());
    }

    public void comicSeriesFound(UUID seriesId) {
        messageSender.sendComicSeriesFound(
                ComicSeriesFoundData.builder().eventType(EventType.COMIC_SERIES_FOUND).seriesId(seriesId).build());
    }

    public void audioFileFound(MediaFileEntity mediaFile, String directoryName) {
        messageSender.sendAudioFileFound(AudioFileFoundData.fromMediaFileEntity(mediaFile), directoryName);
    }

    public void epubFileFound(DirectoryEntity dir, UUID bookId, MediaFileEntity mediaFile) {
        messageSender.sendEpubFileFound(EpubFileFoundData.builder()
                .eventType(EventType.EPUB_FILE_FOUND)
                .directoryEntityUUID(dir.getId())
                .bookEntityUUID(bookId)
                .mediaFileEntityUUID(mediaFile.getId())
                .path(mediaFile.getPath())
                .build(), dir.getName());
    }

    public void comicFileFound(DirectoryEntity dir, UUID bookId, MediaFileEntity mediaFile) {
        messageSender.sendComicFileFound(ComicFileFoundData.builder()
                .eventType(EventType.COMIC_FILE_FOUND)
                .directoryEntityUUID(dir.getId())
                .bookEntityUUID(bookId)
                .mediaFileEntityUUID(mediaFile.getId())
                .path(mediaFile.getPath())
                .build(), dir.getName());
    }

    /** Routes a comic volume's file to the epub or cbz/pdf parser based on the file extension. */
    public void comicVolumeFileFound(DirectoryEntity dir, UUID volumeId, MediaFileEntity mediaFile) {
        if (mediaFile.getPath().toLowerCase().endsWith(".epub")) {
            epubFileFound(dir, volumeId, mediaFile);
        } else {
            comicFileFound(dir, volumeId, mediaFile);
        }
    }

    public void nfoFileFound(DirectoryEntity dir, String path) {
        messageSender.sendNfoFileFound(NfoFileFoundData.builder()
                .eventType(EventType.NFO_FILE_FOUND)
                .directoryEntityUUID(dir.getId())
                .path(path)
                .build(), dir.getName());
    }
}
