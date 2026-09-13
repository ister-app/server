package app.ister.disk.scanner;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.StorageKind;
import app.ister.core.eventdata.FileScanRequestedData;
import app.ister.core.service.MessageSender;
import app.ister.disk.scanner.scanners.AudioScanner;
import app.ister.disk.scanner.scanners.ComicScanner;
import app.ister.disk.scanner.scanners.EpubScanner;
import app.ister.disk.scanner.scanners.ImageScanner;
import app.ister.disk.scanner.scanners.MediaFileScanner;
import app.ister.disk.scanner.scanners.NfoScanner;
import app.ister.disk.scanner.scanners.SubtitleScanner;
import app.ister.disk.storage.FakeObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3LibraryScannerTest {
    @Mock private ScannedCache scannedCache;
    @Mock private MessageSender messageSender;
    @Mock private MediaFileScanner mediaFileScanner;
    @Mock private ImageScanner imageScanner;
    @Mock private NfoScanner nfoScanner;
    @Mock private SubtitleScanner subtitleScanner;
    @Mock private AudioScanner audioScanner;
    @Mock private EpubScanner epubScanner;
    @Mock private ComicScanner comicScanner;

    private DirectoryEntity showDirectory(String prefix) {
        DirectoryEntity dir = DirectoryEntity.builder()
                .name("shows-s3")
                .path(prefix.isEmpty() ? "s3://bucket" : "s3://bucket/" + prefix)
                .storageKind(StorageKind.S3).s3Connection("minio").s3Bucket("bucket").s3Prefix(prefix)
                .libraryEntity(LibraryEntity.builder().libraryType(LibraryType.SHOW).name("shows").build())
                .directoryType(DirectoryType.LIBRARY).build();
        org.springframework.test.util.ReflectionTestUtils.setField(dir, "id", UUID.randomUUID());
        return dir;
    }

    private S3LibraryScanner scanner(DirectoryEntity dir, FakeObjectStore store) {
        return new S3LibraryScanner(dir, store, new ScanEntryDispatcher(dir, scannedCache, messageSender,
                new Scanners(mediaFileScanner, imageScanner, nfoScanner, subtitleScanner, audioScanner, epubScanner, comicScanner)));
    }

    /** Only show/season prefixes are descended into; the pruned subtree is never listed, and dot-prefixes are skipped. */
    @Test
    void walksTheShowLayoutAndPrunesLikeTheFilesystemWalk() {
        FakeObjectStore store = new FakeObjectStore("bucket")
                .put("media/shows/Show (2024)/Season 01/s01e01.mkv", "video")
                .put("media/shows/Show (2024)/Season 01/s01e01.en.srt", "sub")
                .put("media/shows/Show (2024)/tvshow.nfo", "<tvshow/>")
                .put("media/shows/.trash/Old (2000)/Season 01/s01e01.mkv", "x")
                .put("media/shows/random-folder/s01e01.mkv", "x")
                .put("media/movies/Movie (2020)/Movie (2020).mkv", "not in this directory");
        DirectoryEntity dir = showDirectory("media/shows");
        lenient().when(mediaFileScanner.analyzable(any(), anyBoolean(), anyLong()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).endsWith(".mkv"));
        lenient().when(subtitleScanner.analyzable(any(), anyBoolean(), anyLong()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).endsWith(".srt"));
        lenient().when(nfoScanner.analyzable(any(), anyBoolean(), anyLong()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).endsWith(".nfo"));

        scanner(dir, store).scan();

        ArgumentCaptor<FileScanRequestedData> events = ArgumentCaptor.forClass(FileScanRequestedData.class);
        verify(messageSender, org.mockito.Mockito.times(3)).sendFileScanRequested(events.capture(), eq("shows-s3"));
        assertThat(events.getAllValues()).extracting(FileScanRequestedData::getPath).containsExactlyInAnyOrder(
                "s3://bucket/media/shows/Show (2024)/Season 01/s01e01.mkv",
                "s3://bucket/media/shows/Show (2024)/Season 01/s01e01.en.srt",
                "s3://bucket/media/shows/Show (2024)/tvshow.nfo");
        assertThat(events.getAllValues()).allSatisfy(e -> {
            assertThat(e.getRegularFile()).isTrue();
            assertThat(e.getSize()).isPositive();
            assertThat(e.getLastModified()).isNotNull();
            assertThat(e.getDirectoryEntityUUID()).isEqualTo(dir.getId());
        });
        // the seen paths are recorded on the cache so the zombie sweep keeps them
        verify(scannedCache).foundMediaFilePath("s3://bucket/media/shows/Show (2024)/Season 01/s01e01.mkv");
    }

    @Test
    void scansFromTheBucketRootWhenThePrefixIsEmpty() {
        FakeObjectStore store = new FakeObjectStore("bucket")
                .put("Show (2024)/Season 01/s01e01.mkv", "video");
        DirectoryEntity dir = showDirectory("");
        when(mediaFileScanner.analyzable(any(), anyBoolean(), anyLong())).thenReturn(true);
        lenient().when(imageScanner.analyzable(any(), anyBoolean(), anyLong())).thenReturn(false);

        scanner(dir, store).scan();

        ArgumentCaptor<FileScanRequestedData> events = ArgumentCaptor.forClass(FileScanRequestedData.class);
        verify(messageSender).sendFileScanRequested(events.capture(), eq("shows-s3"));
        assertThat(events.getValue().getPath()).isEqualTo("s3://bucket/Show (2024)/Season 01/s01e01.mkv");
    }

    @Test
    void refusesADirectoryWhoseUriIsOnAnotherBucket() {
        DirectoryEntity dir = showDirectory("media");
        dir.setPath("s3://other-bucket/media");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> scanner(dir, new FakeObjectStore("bucket")).scan());
    }

    /** DirectoryPruner is what both walks share; a quick check of the per-type rules on S3 uris. */
    @Test
    void prunerAppliesTheLayoutRulesToS3Uris() {
        DirectoryEntity shows = showDirectory("media/shows");
        assertThat(DirectoryPruner.shouldDescend(shows, "s3://bucket/media/shows")).isTrue();
        assertThat(DirectoryPruner.shouldDescend(shows, "s3://bucket/media/shows/Show (2024)")).isTrue();
        assertThat(DirectoryPruner.shouldDescend(shows, "s3://bucket/media/shows/Show (2024)/Season 01")).isTrue();
        assertThat(DirectoryPruner.shouldDescend(shows, "s3://bucket/media/shows/.hidden")).isFalse();
        assertThat(DirectoryPruner.shouldDescend(shows, "s3://bucket/media/shows/junk")).isFalse();

        DirectoryEntity music = showDirectory("music");
        music.setLibraryEntity(LibraryEntity.builder().libraryType(LibraryType.MUSIC).name("music").build());
        assertThat(DirectoryPruner.shouldDescend(music, "s3://bucket/music/R.E.M.")).isTrue();
        assertThat(DirectoryPruner.shouldDescend(music, "s3://bucket/music/R.E.M./Green (1988)")).isTrue();
    }
}
