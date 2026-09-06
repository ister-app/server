package app.ister.core.status;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.TrackEntity;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Derives the activity-screen description of a piece of work from the entities a
 * handler already holds, so every handler describes the same media file the same way:
 * the file name as subject (prefixed with "S06E22" for an episode), the show / movie /
 * album / book / podcast as context, and the directory and library it lives in.
 * Pure functions over the entity graph; callers must hold an open session for the
 * lazy associations they pass in.
 */
public final class ActivitySubjects {

    public static final String SHOW = "show";
    public static final String MOVIE = "movie";
    public static final String ALBUM = "album";
    public static final String BOOK = "book";
    public static final String PODCAST = "podcast";
    public static final String PERSON = "person";
    public static final String LIBRARY = "library";

    /** Everything ActivityContext can report about one work item; any part may be null. */
    public record Subject(String title, String contextType, String contextId, String context,
                          String directory, String library) {

        public Subject withTitle(String newTitle) {
            return new Subject(newTitle, contextType, contextId, context, directory, library);
        }
    }

    private ActivitySubjects() {
    }

    public static Subject describe(MediaFileEntity mediaFile) {
        if (mediaFile == null) {
            return null;
        }
        String fileName = fileName(mediaFile.getPath());
        Subject base = describe(mediaFile.getDirectoryEntity());
        if (mediaFile.getEpisodeEntity() != null) {
            EpisodeEntity episode = mediaFile.getEpisodeEntity();
            Subject show = describe(episode.getShowEntity(), base);
            return show.withTitle(join(episodeCode(episode), fileName));
        }
        if (mediaFile.getMovieEntity() != null) {
            return describe(mediaFile.getMovieEntity(), base).withTitle(fileName);
        }
        if (mediaFile.getTrackEntity() != null) {
            TrackEntity track = mediaFile.getTrackEntity();
            return describe(track.getAlbumEntity(), base).withTitle(fileName);
        }
        if (mediaFile.getBookEntity() != null) {
            return describe(mediaFile.getBookEntity(), base).withTitle(fileName);
        }
        if (mediaFile.getChapterEntity() != null && mediaFile.getChapterEntity().getBookEntity() != null) {
            return describe(mediaFile.getChapterEntity().getBookEntity(), base).withTitle(fileName);
        }
        if (mediaFile.getPodcastEpisodeEntity() != null) {
            return describe(mediaFile.getPodcastEpisodeEntity().getPodcastEntity(), base).withTitle(fileName);
        }
        return base.withTitle(fileName);
    }

    /** Directory and library only, no context; the title stays empty. */
    public static Subject describe(DirectoryEntity directory) {
        if (directory == null) {
            return new Subject(null, null, null, null, null, null);
        }
        String library = directory.getLibraryEntity() == null ? null : directory.getLibraryEntity().getName();
        return new Subject(null, null, null, null, directory.getName(), library);
    }

    /** A directory-level job (scan, sweep): the directory is the subject and the library the context. */
    public static Subject describeDirectoryJob(DirectoryEntity directory) {
        Subject base = describe(directory);
        if (directory != null && directory.getLibraryEntity() != null) {
            return new Subject(directory.getName(), LIBRARY, id(directory.getLibraryEntity().getId()),
                    directory.getLibraryEntity().getName(), base.directory(), base.library());
        }
        return base.withTitle(directory == null ? null : directory.getName());
    }

    public static Subject describe(ShowEntity show, Subject base) {
        if (show == null) {
            return base;
        }
        return new Subject(base.title(), SHOW, id(show.getId()), show.getName(),
                base.directory(), base.library());
    }

    public static Subject describe(SeasonEntity season, Subject base) {
        if (season == null) {
            return base;
        }
        return describe(season.getShowEntity(), base).withTitle(seasonCode(season));
    }

    public static Subject describe(EpisodeEntity episode, Subject base) {
        if (episode == null) {
            return base;
        }
        return describe(episode.getShowEntity(), base).withTitle(episodeCode(episode));
    }

    public static Subject describe(MovieEntity movie, Subject base) {
        if (movie == null) {
            return base;
        }
        String title = movie.getReleaseYear() > 0 ? movie.getName() + " (" + movie.getReleaseYear() + ")" : movie.getName();
        return new Subject(base.title(), MOVIE, id(movie.getId()), title, base.directory(), base.library());
    }

    public static Subject describe(AlbumEntity album, Subject base) {
        if (album == null) {
            return base;
        }
        String artist = album.getPersonEntity() == null ? null : album.getPersonEntity().getName();
        return new Subject(base.title(), ALBUM, id(album.getId()), join(artist, album.getName(), " – "),
                base.directory(), base.library());
    }

    public static Subject describe(BookEntity book, Subject base) {
        if (book == null) {
            return base;
        }
        String author = book.getPersonEntity() == null ? null : book.getPersonEntity().getName();
        String name = book.getTitle() != null && !book.getTitle().isBlank() ? book.getTitle() : book.getName();
        return new Subject(base.title(), BOOK, id(book.getId()), join(author, name, " – "),
                base.directory(), base.library());
    }

    public static Subject describe(PodcastEntity podcast, Subject base) {
        if (podcast == null) {
            return base;
        }
        return new Subject(base.title(), PODCAST, id(podcast.getId()), podcast.getTitle(),
                base.directory(), base.library());
    }

    public static Subject empty() {
        return new Subject(null, null, null, null, null, null);
    }

    /** "S06E22" — zero-padded so the codes sort and align. */
    public static String episodeCode(EpisodeEntity episode) {
        SeasonEntity season = episode.getSeasonEntity();
        String seasonPart = season == null ? "" : String.format("S%02d", season.getNumber());
        return seasonPart + String.format("E%02d", episode.getNumber());
    }

    public static String seasonCode(SeasonEntity season) {
        return String.format("S%02d", season.getNumber());
    }

    public static String fileName(String path) {
        if (path == null) {
            return null;
        }
        Path name = Path.of(path).getFileName();
        return name == null ? path : name.toString();
    }

    private static String id(UUID id) {
        return id == null ? null : id.toString();
    }

    private static String join(String a, String b) {
        return join(a, b, " · ");
    }

    private static String join(String a, String b, String separator) {
        if (a == null || a.isBlank()) {
            return b;
        }
        if (b == null || b.isBlank()) {
            return a;
        }
        return a + separator + b;
    }
}
