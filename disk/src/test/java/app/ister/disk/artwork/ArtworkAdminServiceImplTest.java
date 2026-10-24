package app.ister.disk.artwork;

import app.ister.core.artwork.ImageTarget;
import app.ister.core.artwork.RemoteArtworkProvider;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.MetadataSource;
import app.ister.core.node.MediaFileInputResolver;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MediaFileRepository;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.utils.BackgroundFfmpeg;
import app.ister.disk.events.mediafilefound.MediaFileFoundCreateBackground;
import app.ister.disk.storage.CacheStoreMocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtworkAdminServiceImplTest {

    @TempDir
    Path tmp;

    private final ImageRepository imageRepository = mock(ImageRepository.class);
    private final MediaFileRepository mediaFileRepository = mock(MediaFileRepository.class);
    private final CacheDirectoryResolver resolver = mock(CacheDirectoryResolver.class);
    private final MediaFileInputResolver inputResolver = mock(MediaFileInputResolver.class);
    private final MediaFileFoundCreateBackground createBackground = mock(MediaFileFoundCreateBackground.class);
    private final RemoteArtworkProvider provider = mock(RemoteArtworkProvider.class);
    private final BackgroundFfmpeg backgroundFfmpeg = mock(BackgroundFfmpeg.class);

    private ArtworkAdminServiceImpl subject;
    private final UUID movieId = UUID.randomUUID();
    private final ImageTarget movie = new ImageTarget(ImageTarget.Kind.MOVIE, movieId);
    private DirectoryEntity cacheDir;

    @BeforeEach
    void setUp() {
        cacheDir = DirectoryEntity.builder().id(UUID.randomUUID()).name("cache").path("/cache")
                .directoryType(DirectoryType.CACHE).build();
        when(resolver.forThisNode()).thenReturn(cacheDir);
        CacheStoreMocks.fake(resolver);
        when(backgroundFfmpeg.dir()).thenReturn("/usr/bin");
        when(imageRepository.save(any())).thenAnswer(inv -> {
            ImageEntity image = inv.getArgument(0);
            if (image.getId() == null) {
                image.setId(UUID.randomUUID());
            }
            return image;
        });
        subject = new ArtworkAdminServiceImpl(imageRepository, mediaFileRepository, resolver, inputResolver,
                createBackground, List.of(provider), backgroundFfmpeg, tmp.toString());
    }

    @Test
    void selectMarksTheImageAndClearsTheSiblingOfTheSameType() {
        ImageEntity oldChoice = image(ImageType.COVER, true);
        ImageEntity background = image(ImageType.BACKGROUND, true);
        ImageEntity wanted = image(ImageType.COVER, false);
        when(imageRepository.findById(wanted.getId())).thenReturn(Optional.of(wanted));
        when(imageRepository.findByMovieEntityId(movieId)).thenReturn(List.of(oldChoice, background, wanted));

        ImageEntity result = subject.select(wanted.getId());

        assertThat(result.isSelected()).isTrue();
        assertThat(oldChoice.isSelected()).isFalse();
        // another type's choice is untouched
        assertThat(background.isSelected()).isTrue();
        verify(imageRepository).saveAll(List.of(oldChoice));
    }

    @Test
    void storeUploadWritesIntoTheCacheAndSelectsTheNewRow() throws IOException {
        when(imageRepository.findByMovieEntityId(movieId)).thenReturn(List.of());

        ImageEntity stored = subject.storeUpload(movie, ImageType.BACKGROUND, png(), "image/png", "upload://u/1");

        assertThat(stored.getPath()).startsWith("/cache/artwork/").endsWith(".png");
        assertThat(stored.getDirectoryEntityId()).isEqualTo(cacheDir.getId());
        assertThat(stored.getMovieEntityId()).isEqualTo(movieId);
        assertThat(stored.getType()).isEqualTo(ImageType.BACKGROUND);
        assertThat(stored.getSource()).isEqualTo(MetadataSource.USER);
        assertThat(stored.isSelected()).isTrue();
        assertThat(stored.getBlurHash()).isNotBlank();
    }

    @Test
    void storeUploadRejectsUnknownContentTypes() {
        assertThatThrownBy(() -> subject.storeUpload(movie, ImageType.COVER, new byte[]{1}, "image/gif", "upload://u/1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectRemoteReusesAnAlreadyStoredCandidateWithoutDownloading() throws IOException {
        ImageEntity stored = image(ImageType.COVER, false);
        stored.setSourceUri("TMDB://https://image.tmdb.org/t/p/original/a.jpg");
        when(provider.downloadUrl(stored.getSourceUri())).thenReturn(Optional.of("https://image.tmdb.org/t/p/original/a.jpg"));
        when(imageRepository.findByMovieEntityId(movieId)).thenReturn(List.of(stored));

        ImageEntity result = subject.selectRemote(movie, ImageType.COVER, stored.getSourceUri());

        assertThat(result).isSameAs(stored);
        assertThat(result.isSelected()).isTrue();
    }

    @Test
    void selectRemoteRefusesAUriNoProviderOwns() {
        when(provider.downloadUrl(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> subject.selectRemote(movie, ImageType.COVER, "https://evil.example/x.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectVideoFrameOnlyForVideoItems() {
        ImageTarget album = new ImageTarget(ImageTarget.Kind.ALBUM, UUID.randomUUID());
        assertThatThrownBy(() -> subject.selectVideoFrame(album, ImageType.COVER, UUID.randomUUID(), 1000))
                .isInstanceOf(IllegalArgumentException.class);
        verify(createBackground, never()).createBackground(any(), any(), any(), any(Long.class));
    }

    @Test
    void deleteUserImageRefusesScannedAndProviderImages() {
        ImageEntity scanned = image(ImageType.COVER, false);
        scanned.setSource(MetadataSource.TMDB);
        when(imageRepository.findById(scanned.getId())).thenReturn(Optional.of(scanned));

        assertThatThrownBy(() -> subject.deleteUserImage(scanned.getId())).isInstanceOf(IllegalArgumentException.class);
        verify(imageRepository, never()).delete(any());

        ImageEntity uploaded = image(ImageType.COVER, true);
        uploaded.setSource(MetadataSource.USER);
        when(imageRepository.findById(uploaded.getId())).thenReturn(Optional.of(uploaded));
        assertThat(subject.deleteUserImage(uploaded.getId())).isTrue();
        verify(imageRepository).delete(uploaded);
    }

    @Test
    void clearSelectedDropsTheChoiceForThatTypeOnly() {
        ImageEntity cover = image(ImageType.COVER, true);
        ImageEntity background = image(ImageType.BACKGROUND, true);
        when(imageRepository.findByMovieEntityId(movieId)).thenReturn(List.of(cover, background));

        assertThat(subject.clearSelected(movie, ImageType.COVER)).isTrue();

        assertThat(cover.isSelected()).isFalse();
        assertThat(background.isSelected()).isTrue();
        assertThat(subject.clearSelected(movie, ImageType.COVER)).isFalse();
    }

    private ImageEntity image(ImageType type, boolean selected) {
        ImageEntity image = ImageEntity.builder().id(UUID.randomUUID()).type(type).selected(selected)
                .movieEntityId(movieId).directoryEntityId(cacheDir.getId()).path("/cache/" + UUID.randomUUID() + ".jpg").build();
        return image;
    }

    private static byte[] png() throws IOException {
        BufferedImage bi = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 4; y++) {
                bi.setRGB(x, y, x * 30 << 16 | y * 60);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(bi, "png", out);
        return out.toByteArray();
    }
}
