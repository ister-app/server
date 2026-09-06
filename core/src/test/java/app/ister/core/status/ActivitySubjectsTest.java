package app.ister.core.status;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.TrackEntity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ActivitySubjectsTest {

    private static DirectoryEntity directory() {
        LibraryEntity library = LibraryEntity.builder().id(UUID.randomUUID()).name("Series").build();
        return DirectoryEntity.builder().id(UUID.randomUUID()).name("disk1").path("/media/disk1")
                .libraryEntity(library).build();
    }

    @Test
    void episodeFileIsGroupedUnderItsShowWithTheEpisodeCodeInTheTitle() {
        ShowEntity show = ShowEntity.builder().id(UUID.randomUUID()).name("Seinfeld").build();
        SeasonEntity season = SeasonEntity.builder().id(UUID.randomUUID()).showEntity(show).number(6).build();
        EpisodeEntity episode = EpisodeEntity.builder().id(UUID.randomUUID()).showEntity(show)
                .seasonEntity(season).number(22).build();
        MediaFileEntity file = MediaFileEntity.builder().directoryEntity(directory())
                .path("/media/disk1/Seinfeld/Season 6/s06e22.mkv").episodeEntity(episode).build();

        ActivitySubjects.Subject subject = ActivitySubjects.describe(file);

        assertEquals("S06E22 · s06e22.mkv", subject.title());
        assertEquals(ActivitySubjects.SHOW, subject.contextType());
        assertEquals(show.getId().toString(), subject.contextId());
        assertEquals("Seinfeld", subject.context());
        assertEquals("disk1", subject.directory());
        assertEquals("Series", subject.library());
    }

    @Test
    void movieFileCarriesTheReleaseYear() {
        MovieEntity movie = MovieEntity.builder().id(UUID.randomUUID()).name("Die Hard").releaseYear(1988).build();
        MediaFileEntity file = MediaFileEntity.builder().directoryEntity(directory())
                .path("/media/disk1/Die Hard (1988)/movie.mkv").movieEntity(movie).build();

        ActivitySubjects.Subject subject = ActivitySubjects.describe(file);

        assertEquals("movie.mkv", subject.title());
        assertEquals(ActivitySubjects.MOVIE, subject.contextType());
        assertEquals("Die Hard (1988)", subject.context());
    }

    @Test
    void trackIsGroupedUnderArtistAndAlbum() {
        PersonEntity artist = PersonEntity.builder().id(UUID.randomUUID()).name("Metallica").build();
        AlbumEntity album = AlbumEntity.builder().id(UUID.randomUUID()).name("Ride the Lightning")
                .personEntity(artist).build();
        TrackEntity track = TrackEntity.builder().id(UUID.randomUUID()).albumEntity(album).build();
        MediaFileEntity file = MediaFileEntity.builder().path("/music/01 - Fight Fire with Fire.flac")
                .trackEntity(track).build();

        ActivitySubjects.Subject subject = ActivitySubjects.describe(file);

        assertEquals("01 - Fight Fire with Fire.flac", subject.title());
        assertEquals(ActivitySubjects.ALBUM, subject.contextType());
        assertEquals("Metallica – Ride the Lightning", subject.context());
        assertNull(subject.directory());
    }

    @Test
    void unlinkedFileKeepsOnlyFileNameAndDirectory() {
        MediaFileEntity file = MediaFileEntity.builder().directoryEntity(directory()).path("/media/disk1/loose.mkv").build();

        ActivitySubjects.Subject subject = ActivitySubjects.describe(file);

        assertEquals("loose.mkv", subject.title());
        assertNull(subject.contextType());
        assertEquals("disk1", subject.directory());
    }

    @Test
    void directoryJobIsGroupedUnderTheLibrary() {
        DirectoryEntity directory = directory();

        ActivitySubjects.Subject subject = ActivitySubjects.describeDirectoryJob(directory);

        assertEquals("disk1", subject.title());
        assertEquals(ActivitySubjects.LIBRARY, subject.contextType());
        assertEquals("Series", subject.context());
        assertEquals(directory.getLibraryEntity().getId().toString(), subject.contextId());
    }

    @Test
    void seasonIsDescribedAsShowContextWithSeasonCode() {
        ShowEntity show = ShowEntity.builder().id(UUID.randomUUID()).name("Seinfeld").build();
        SeasonEntity season = SeasonEntity.builder().showEntity(show).number(6).build();

        ActivitySubjects.Subject subject = ActivitySubjects.describe(season, ActivitySubjects.empty());

        assertEquals("S06", subject.title());
        assertEquals("Seinfeld", subject.context());
    }
}
