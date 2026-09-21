package app.ister.disk.upload;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.LibraryType;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.repository.UploadFileRepository;
import app.ister.core.storage.LibraryWriteStoreResolver;
import app.ister.disk.scanner.Scanners;
import app.ister.disk.scanner.scanners.AudioScanner;
import app.ister.disk.scanner.scanners.ComicScanner;
import app.ister.disk.scanner.scanners.EpubScanner;
import app.ister.disk.scanner.scanners.ImageScanner;
import app.ister.disk.scanner.scanners.MediaFileScanner;
import app.ister.disk.scanner.scanners.NfoScanner;
import app.ister.disk.scanner.scanners.SubtitleScanner;
import app.ister.disk.upload.UploadDtos.Entry;
import app.ister.disk.upload.UploadDtos.IgnoreReason;
import app.ister.disk.upload.UploadDtos.PlanRequest;
import app.ister.disk.upload.UploadDtos.PreviewEntry;
import app.ister.disk.upload.UploadDtos.PreviewResponse;
import app.ister.disk.upload.UploadDtos.PreviewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The preview against the REAL scanner decisions: only the scanners' collaborators are absent. */
class UploadPreviewServiceTest {

    private final MediaFileRepository mediaFileRepository = mock(MediaFileRepository.class);
    private final ImageRepository imageRepository = mock(ImageRepository.class);
    private final OtherPathFileRepository otherPathFileRepository = mock(OtherPathFileRepository.class);
    private final UploadFileRepository uploadFileRepository = mock(UploadFileRepository.class);
    private final LibraryWriteStoreResolver writeStoreResolver = mock(LibraryWriteStoreResolver.class);
    private UploadPreviewService service;

    @BeforeEach
    void setUp() {
        // analyzable() is pure path logic, so the real methods run fine on otherwise empty scanners
        Scanners scanners = new Scanners(
                mock(MediaFileScanner.class, CALLS_REAL_METHODS), mock(ImageScanner.class, CALLS_REAL_METHODS),
                mock(NfoScanner.class, CALLS_REAL_METHODS), mock(SubtitleScanner.class, CALLS_REAL_METHODS),
                mock(AudioScanner.class, CALLS_REAL_METHODS), mock(EpubScanner.class, CALLS_REAL_METHODS),
                mock(ComicScanner.class, CALLS_REAL_METHODS));
        service = new UploadPreviewService(scanners, mediaFileRepository, imageRepository, otherPathFileRepository,
                uploadFileRepository, writeStoreResolver);
    }

    private static DirectoryEntity directory(LibraryType type, String path) {
        DirectoryEntity directory = DirectoryEntity.builder()
                .name(type.name().toLowerCase()).path(path).directoryType(DirectoryType.LIBRARY)
                .libraryEntity(LibraryEntity.builder().name(type.name()).libraryType(type).build())
                .build();
        directory.setId(UUID.randomUUID());
        return directory;
    }

    private static PlanRequest request(String targetParent, String rootName, boolean overwrite, String... paths) {
        return new PlanRequest(null, targetParent, rootName, overwrite,
                Arrays.stream(paths).map(p -> new Entry(p, 100)).toList());
    }

    private static PreviewEntry entry(PreviewResponse response, String relativePath) {
        return response.entries().stream().filter(e -> e.relativePath().equals(relativePath)).findFirst().orElseThrow();
    }

    @Test
    void wholeShowWithARenamedRoot() {
        DirectoryEntity shows = directory(LibraryType.SHOW, "/media/shows");
        PreviewResponse response = service.preview(shows, request(null, "The Show (2019)", false,
                "Season 01/The.Show.s01e01.mkv", "Season 01/The.Show.s01e01.srt", "Season 04/The.Show.s04e06-e07.mkv",
                "poster.jpg", "notes.txt"));

        PreviewEntry episode = entry(response, "Season 01/The.Show.s01e01.mkv");
        assertThat(episode.status()).isEqualTo(PreviewStatus.RECOGNISED);
        assertThat(episode.targetPath()).isEqualTo("/media/shows/The Show (2019)/Season 01/The.Show.s01e01.mkv");
        assertThat(episode.recognition().title()).isEqualTo("The Show");
        assertThat(episode.recognition().year()).isEqualTo(2019);
        assertThat(episode.recognition().season()).isEqualTo(1);
        assertThat(episode.recognition().episodes()).containsExactly(1);
        assertThat(entry(response, "Season 04/The.Show.s04e06-e07.mkv").recognition().episodes()).containsExactly(6, 7);
        assertThat(entry(response, "Season 01/The.Show.s01e01.srt").status()).isEqualTo(PreviewStatus.RECOGNISED);
        assertThat(entry(response, "poster.jpg").status()).isEqualTo(PreviewStatus.RECOGNISED);

        PreviewEntry notes = entry(response, "notes.txt");
        assertThat(notes.status()).isEqualTo(PreviewStatus.IGNORED);
        assertThat(notes.ignoreReason()).isEqualTo(IgnoreReason.UNSUPPORTED_FILE);

        assertThat(response.roots()).singleElement().satisfies(root -> {
            assertThat(root.name()).isEqualTo("The Show (2019)");
            assertThat(root.level()).isEqualTo("SHOW");
        });
        assertThat(response.uploadFiles()).isEqualTo(4);
        assertThat(response.uploadBytes()).isEqualTo(400);
    }

    @Test
    void showFolderWithoutAYearIsNotScanned() {
        DirectoryEntity shows = directory(LibraryType.SHOW, "/media/shows");
        PreviewResponse response = service.preview(shows, request(null, "The Show", false,
                "Season 01/The.Show.s01e01.mkv"));

        PreviewEntry episode = entry(response, "Season 01/The.Show.s01e01.mkv");
        assertThat(episode.status()).isEqualTo(PreviewStatus.IGNORED);
        assertThat(episode.ignoreReason()).isEqualTo(IgnoreReason.FOLDER_NOT_SCANNED);
        assertThat(episode.detail()).isEqualTo("The Show");
        assertThat(response.uploadFiles()).isZero();
    }

    @Test
    void albumUnderAnArtistWhoseNameHasDots() {
        DirectoryEntity music = directory(LibraryType.MUSIC, "/media/music");
        PreviewResponse response = service.preview(music, request("R.E.M.", "Out of Time (1991)", false,
                "01 - Radio Song.flac", "cover.jpg", "booklet.pdf"));

        PreviewEntry track = entry(response, "01 - Radio Song.flac");
        assertThat(track.status()).isEqualTo(PreviewStatus.RECOGNISED);
        assertThat(track.targetPath()).isEqualTo("/media/music/R.E.M./Out of Time (1991)/01 - Radio Song.flac");
        assertThat(track.recognition().artist()).isEqualTo("R.E.M.");
        assertThat(track.recognition().album()).isEqualTo("Out of Time");
        assertThat(track.recognition().track()).isEqualTo(1);
        assertThat(entry(response, "cover.jpg").status()).isEqualTo(PreviewStatus.RECOGNISED);
        assertThat(entry(response, "booklet.pdf").status()).isEqualTo(PreviewStatus.IGNORED);
        assertThat(response.roots()).singleElement().satisfies(root -> assertThat(root.level()).isEqualTo("ALBUM"));
    }

    @Test
    void folderFullOfArtistsWithoutARootName() {
        DirectoryEntity music = directory(LibraryType.MUSIC, "/media/music");
        PreviewResponse response = service.preview(music, request("", null, false,
                "Artist A/Album (2001)/01 - One.mp3", "Artist B/Album (2002)/01 - Two.mp3"));

        assertThat(response.entries()).allSatisfy(e -> assertThat(e.status()).isEqualTo(PreviewStatus.RECOGNISED));
        assertThat(response.roots()).extracting("name", "level")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Artist A", "ARTIST"),
                        org.assertj.core.groups.Tuple.tuple("Artist B", "ARTIST"));
    }

    @Test
    void albumDroppedInTheMusicRootShowsUpAsAnArtist() {
        DirectoryEntity music = directory(LibraryType.MUSIC, "/media/music");
        PreviewResponse response = service.preview(music, request(null, "Out of Time (1991)", false,
                "01 - Radio Song.flac"));
        // the misplacement the admin has to be able to see before uploading
        assertThat(response.roots()).singleElement().satisfies(root -> assertThat(root.level()).isEqualTo("ARTIST"));
    }

    @Test
    void bookAuthorWithDotsAndAudiobookChapters() {
        DirectoryEntity books = directory(LibraryType.BOOK, "/media/books");
        PreviewResponse response = service.preview(books, request(null, "J.K. Rowling", false,
                "Harry Potter (1997).epub", "Harry Potter (1997)/001_Chapter.mp3"));

        PreviewEntry epub = entry(response, "Harry Potter (1997).epub");
        assertThat(epub.status()).isEqualTo(PreviewStatus.RECOGNISED);
        assertThat(epub.recognition().author()).isEqualTo("J.K. Rowling");
        PreviewEntry chapter = entry(response, "Harry Potter (1997)/001_Chapter.mp3");
        assertThat(chapter.status()).isEqualTo(PreviewStatus.RECOGNISED);
        assertThat(chapter.recognition().chapter()).isEqualTo(1);
    }

    @Test
    void comicVolumeAndTooDeepNesting() {
        DirectoryEntity comics = directory(LibraryType.COMIC, "/media/comics");
        PreviewResponse response = service.preview(comics, request(null, "Dr. Stone (2017)", false,
                "Volume 27.cbz", "extras/deep/Volume 99.cbz"));

        PreviewEntry volume = entry(response, "Volume 27.cbz");
        assertThat(volume.status()).isEqualTo(PreviewStatus.RECOGNISED);
        assertThat(volume.recognition().series()).isEqualTo("Dr. Stone");
        assertThat(volume.recognition().volume()).isEqualTo(27.0);
        assertThat(entry(response, "extras/deep/Volume 99.cbz").status()).isEqualTo(PreviewStatus.IGNORED);
    }

    @Test
    void flagsExistingBusyInvalidAndDuplicateEntries() {
        DirectoryEntity movies = directory(LibraryType.MOVIE, "/media/movies");
        when(mediaFileRepository.findPathsByDirectoryEntityIdAndPathIn(any(), anyCollection()))
                .thenReturn(List.of("/media/movies/Old (2001)/Old (2001).mkv"));
        when(uploadFileRepository.findActiveTargetPathsIn(anyCollection()))
                .thenReturn(List.of("/media/movies/Busy (2002)/Busy (2002).mkv"));

        PreviewResponse response = service.preview(movies, request(null, null, true,
                "Old (2001)/Old (2001).mkv", "Busy (2002)/Busy (2002).mkv", "New (2003)/New (2003).mkv",
                "New (2003)/New (2003).mkv", "../escape (2004).mkv", ".hidden/Film (2005).mkv"));

        assertThat(response.entries()).extracting(PreviewEntry::status).containsExactly(
                PreviewStatus.EXISTS, PreviewStatus.BUSY, PreviewStatus.RECOGNISED,
                PreviewStatus.DUPLICATE, PreviewStatus.INVALID, PreviewStatus.INVALID);
        // overwrite is on: the existing one counts, the busy one never does
        assertThat(response.uploadFiles()).isEqualTo(2);
        assertThat(response.roots()).extracting("level").containsOnly("MOVIE");
    }

    @Test
    void refusesPodcastLibrariesAndCacheDirectories() {
        DirectoryEntity podcasts = directory(LibraryType.PODCAST, "/media/podcasts");
        PlanRequest empty = request(null, null, false);
        assertThatThrownBy(() -> service.preview(podcasts, empty))
                .isInstanceOf(IllegalArgumentException.class);

        DirectoryEntity cache = DirectoryEntity.builder().name("cache").path("/cache")
                .directoryType(DirectoryType.CACHE).build();
        assertThatThrownBy(() -> service.preview(cache, empty))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAnUnstorableRootName() {
        DirectoryEntity shows = directory(LibraryType.SHOW, "/media/shows");
        PlanRequest escaping = request(null, "../elsewhere", false, "a.mkv");
        assertThatThrownBy(() -> service.preview(shows, escaping))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
