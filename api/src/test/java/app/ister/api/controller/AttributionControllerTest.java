package app.ister.api.controller;

import app.ister.api.controller.AttributionController.Attribution;
import app.ister.core.enums.MetadataSource;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MetadataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttributionControllerTest {

    @InjectMocks
    private AttributionController subject;

    @Mock
    private MetadataRepository metadataRepository;

    @Mock
    private ImageRepository imageRepository;

    @Test
    void unionsMetadataAndImageSourcesAndDropsNonExternalOnes() {
        when(metadataRepository.findDistinctSources())
                .thenReturn(List.of(MetadataSource.TMDB, MetadataSource.WIKIPEDIA, MetadataSource.LOCAL_FILE));
        when(imageRepository.findDistinctSources())
                .thenReturn(List.of(MetadataSource.TMDB, MetadataSource.COVER_ART_ARCHIVE, MetadataSource.PODCAST_FEED));

        List<Attribution> result = subject.attributions();

        assertEquals(
                List.of(MetadataSource.TMDB, MetadataSource.COVER_ART_ARCHIVE, MetadataSource.WIKIPEDIA),
                result.stream().map(Attribution::source).toList());
    }

    @Test
    void fillsProviderMandatedNoticeAndLicenseText() {
        when(metadataRepository.findDistinctSources())
                .thenReturn(List.of(MetadataSource.TMDB, MetadataSource.WIKIPEDIA));
        when(imageRepository.findDistinctSources()).thenReturn(List.of());

        List<Attribution> result = subject.attributions();

        Attribution tmdb = result.stream().filter(a -> a.source() == MetadataSource.TMDB).findFirst().orElseThrow();
        assertEquals("TMDB", tmdb.name());
        assertEquals("https://www.themoviedb.org", tmdb.url());
        assertEquals("This product uses the TMDB API but is not endorsed or certified by TMDB.", tmdb.notice());
        assertNull(tmdb.license());

        Attribution wikipedia = result.stream().filter(a -> a.source() == MetadataSource.WIKIPEDIA).findFirst().orElseThrow();
        assertEquals("CC BY-SA 4.0", wikipedia.license());
        assertNull(wikipedia.notice());
    }

    @Test
    void returnsEmptyWhenNothingWasFetchedExternally() {
        when(metadataRepository.findDistinctSources()).thenReturn(List.of(MetadataSource.LOCAL_FILE));
        when(imageRepository.findDistinctSources()).thenReturn(List.of());

        assertTrue(subject.attributions().isEmpty());
    }
}
