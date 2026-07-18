package app.ister.worker.events.comicseriesfound;

import app.ister.core.config.LanguageProperties;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.ReadingDirection;
import app.ister.core.eventdata.ComicSeriesFoundData;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.SeriesRepository;
import app.ister.worker.events.tmdbmetadata.ImageDownloadService;
import app.ister.worker.events.wikipedia.WikipediaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandleComicSeriesFoundTest {

    @InjectMocks
    private HandleComicSeriesFound subject;

    @Mock
    private SeriesRepository seriesRepository;
    @Mock
    private MetadataRepository metadataRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private ComicSeriesMetadataProvider metadataProvider;
    @Mock
    private ImageDownloadService imageDownloadService;
    @Mock
    private LanguageProperties languageProperties;

    private final UUID seriesId = UUID.randomUUID();
    private final SeriesEntity series = SeriesEntity.builder().id(seriesId).name("Attack on Titan").build();
    private final ComicSeriesFoundData data = ComicSeriesFoundData.builder()
            .eventType(EventType.COMIC_SERIES_FOUND)
            .seriesId(seriesId)
            .build();

    @Test
    void handles() {
        assertEquals(EventType.COMIC_SERIES_FOUND, subject.handles());
    }

    @Test
    void listenerThrowsOnWrongEventType() {
        ComicSeriesFoundData wrong = ComicSeriesFoundData.builder().eventType(EventType.BOOK_FOUND).build();
        assertThrows(IllegalArgumentException.class, () -> subject.listener(wrong));
    }

    @Test
    void savesOneMetadataRowPerLanguageAndTheThumbnail() throws IOException {
        when(seriesRepository.findById(seriesId)).thenReturn(Optional.of(series));
        when(metadataRepository.findBySeriesEntityId(seriesId)).thenReturn(List.of());
        when(imageRepository.findBySeriesEntityId(seriesId)).thenReturn(List.of());
        when(languageProperties.tags()).thenReturn(List.of("en", "nl"));
        when(languageProperties.iso3("en")).thenReturn("eng");
        when(languageProperties.iso3("nl")).thenReturn("nld");
        when(metadataProvider.fetchSeriesContent("Attack on Titan", List.of("en", "nl")))
                .thenReturn(new WikipediaService.SeriesContent(new WikipediaService.Content(
                        Map.of("en", "A manga series.", "nl", "Een mangaserie."),
                        "https://wiki/aot.jpg"), false));

        subject.handle(data);

        ArgumentCaptor<MetadataEntity> saved = ArgumentCaptor.forClass(MetadataEntity.class);
        verify(metadataRepository, times(2)).save(saved.capture());
        assertEquals(2, saved.getAllValues().stream().map(MetadataEntity::getLanguage).distinct().count());
        saved.getAllValues().forEach(metadata -> {
            assertEquals(series, metadata.getSeriesEntity());
            assertEquals("Attack on Titan", metadata.getTitle());
            assertEquals("wikipedia://Attack on Titan", metadata.getSourceUri());
        });
        verify(imageDownloadService).downloadAndSave(eq("https://wiki/aot.jpg"), eq(ImageType.COVER),
                anyString(), anyString(), any());
    }

    @Test
    void skipsEntirelyWhenMetadataAndImageExist() {
        when(seriesRepository.findById(seriesId)).thenReturn(Optional.of(series));
        when(metadataRepository.findBySeriesEntityId(seriesId))
                .thenReturn(List.of(MetadataEntity.builder().build()));
        when(imageRepository.findBySeriesEntityId(seriesId))
                .thenReturn(List.of(ImageEntity.builder().build()));

        subject.handle(data);

        verifyNoInteractions(metadataProvider, imageDownloadService);
    }

    /** Local artwork (cover.jpg in the series dir, volume covers) wins over the wiki thumbnail. */
    @Test
    void doesNotDownloadAThumbnailWhenTheSeriesHasAnImage() throws IOException {
        when(seriesRepository.findById(seriesId)).thenReturn(Optional.of(series));
        when(metadataRepository.findBySeriesEntityId(seriesId)).thenReturn(List.of());
        when(imageRepository.findBySeriesEntityId(seriesId))
                .thenReturn(List.of(ImageEntity.builder().build()));
        when(languageProperties.tags()).thenReturn(List.of("en"));
        when(languageProperties.iso3("en")).thenReturn("eng");
        when(metadataProvider.fetchSeriesContent("Attack on Titan", List.of("en")))
                .thenReturn(new WikipediaService.SeriesContent(
                        new WikipediaService.Content(Map.of("en", "A manga series."), "https://wiki/aot.jpg"),
                        false));

        subject.handle(data);

        verify(metadataRepository).save(any());
        verify(imageDownloadService, never()).downloadAndSave(anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void noMatchSavesNothing() {
        when(seriesRepository.findById(seriesId)).thenReturn(Optional.of(series));
        when(metadataRepository.findBySeriesEntityId(seriesId)).thenReturn(List.of());
        when(imageRepository.findBySeriesEntityId(seriesId)).thenReturn(List.of());
        when(languageProperties.tags()).thenReturn(List.of("en"));
        when(metadataProvider.fetchSeriesContent("Attack on Titan", List.of("en")))
                .thenReturn(WikipediaService.SeriesContent.EMPTY);

        subject.handle(data);

        verify(metadataRepository, never()).save(any());
        verifyNoInteractions(imageDownloadService);
    }

    @Test
    void aMangaTypedSeriesGetsRtlAsDefaultDirection() {
        when(seriesRepository.findById(seriesId)).thenReturn(Optional.of(series));
        when(metadataRepository.findBySeriesEntityId(seriesId)).thenReturn(List.of());
        when(imageRepository.findBySeriesEntityId(seriesId)).thenReturn(List.of());
        when(languageProperties.tags()).thenReturn(List.of("en"));
        when(languageProperties.iso3("en")).thenReturn("eng");
        when(metadataProvider.fetchSeriesContent("Attack on Titan", List.of("en")))
                .thenReturn(new WikipediaService.SeriesContent(
                        new WikipediaService.Content(Map.of("en", "A manga series."), null), true));

        subject.handle(data);

        assertEquals(ReadingDirection.RTL, series.getDefaultReadingDirection());
        verify(seriesRepository).save(series);
    }

    /** The Wikidata signal is weak: it never overwrites a direction ComicInfo.xml already set. */
    @Test
    void mangaDetectionDoesNotOverwriteAnExistingDirection() {
        series.setDefaultReadingDirection(ReadingDirection.LTR);
        when(seriesRepository.findById(seriesId)).thenReturn(Optional.of(series));
        when(metadataRepository.findBySeriesEntityId(seriesId)).thenReturn(List.of());
        when(imageRepository.findBySeriesEntityId(seriesId)).thenReturn(List.of());
        when(languageProperties.tags()).thenReturn(List.of("en"));
        when(languageProperties.iso3("en")).thenReturn("eng");
        when(metadataProvider.fetchSeriesContent("Attack on Titan", List.of("en")))
                .thenReturn(new WikipediaService.SeriesContent(
                        new WikipediaService.Content(Map.of("en", "A manga series."), null), true));

        subject.handle(data);

        assertEquals(ReadingDirection.LTR, series.getDefaultReadingDirection());
        verify(seriesRepository, never()).save(any());
    }

    @Test
    void aNonMangaSeriesKeepsAnUnsetDirection() {
        when(seriesRepository.findById(seriesId)).thenReturn(Optional.of(series));
        when(metadataRepository.findBySeriesEntityId(seriesId)).thenReturn(List.of());
        when(imageRepository.findBySeriesEntityId(seriesId)).thenReturn(List.of());
        when(languageProperties.tags()).thenReturn(List.of("en"));
        when(languageProperties.iso3("en")).thenReturn("eng");
        when(metadataProvider.fetchSeriesContent("Attack on Titan", List.of("en")))
                .thenReturn(new WikipediaService.SeriesContent(
                        new WikipediaService.Content(Map.of("en", "A series."), null), false));

        subject.handle(data);

        assertNull(series.getDefaultReadingDirection());
        verify(seriesRepository, never()).save(any());
    }
}
