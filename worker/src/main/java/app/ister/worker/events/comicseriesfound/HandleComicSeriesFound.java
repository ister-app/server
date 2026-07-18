package app.ister.worker.events.comicseriesfound;

import app.ister.core.Handle;
import app.ister.core.config.LanguageProperties;
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
import app.ister.worker.events.tmdbmetadata.ImageSave;
import app.ister.worker.events.wikipedia.WikipediaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Map;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_COMIC_SERIES_FOUND;

/**
 * Enriches a comic series with a per-language description and (when the series has no local
 * artwork) a thumbnail, from Wikipedia/Wikidata. Local sources win: cover.jpg in the series
 * directory and covers extracted from the volumes themselves are never overwritten.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class HandleComicSeriesFound implements Handle<ComicSeriesFoundData> {

    private final SeriesRepository seriesRepository;
    private final MetadataRepository metadataRepository;
    private final ImageRepository imageRepository;
    private final ComicSeriesMetadataProvider metadataProvider;
    private final ImageDownloadService imageDownloadService;
    private final LanguageProperties languageProperties;

    @Override
    public EventType handles() {
        return EventType.COMIC_SERIES_FOUND;
    }

    @RabbitListener(queues = APP_ISTER_SERVER_COMIC_SERIES_FOUND)
    @Override
    public void listener(ComicSeriesFoundData data) {
        Handle.super.listener(data);
    }

    @Override
    public void handle(ComicSeriesFoundData data) {
        seriesRepository.findById(data.getSeriesId()).ifPresent(series -> {
            boolean hasMetadata = !metadataRepository.findBySeriesEntityId(series.getId()).isEmpty();
            boolean hasImage = !imageRepository.findBySeriesEntityId(series.getId()).isEmpty();
            if (hasMetadata && hasImage) {
                return;
            }

            WikipediaService.SeriesContent seriesContent =
                    metadataProvider.fetchSeriesContent(series.getName(), languageProperties.tags());
            WikipediaService.Content content = seriesContent.content();
            if (!hasMetadata) {
                saveDescriptions(series, content.bios());
            }
            if (!hasImage && content.thumbnail() != null) {
                downloadThumbnail(series, content.thumbnail());
            }
            // Weak signal: only fills an unset direction, never overwrites an explicit
            // ComicInfo.xml Manga tag (which may legitimately say No).
            if (seriesContent.manga() && series.getDefaultReadingDirection() == null) {
                series.setDefaultReadingDirection(ReadingDirection.RTL);
                seriesRepository.save(series);
            }
        });
    }

    private void saveDescriptions(SeriesEntity series, Map<String, String> descriptionsByTag) {
        descriptionsByTag.forEach((tag, description) -> metadataRepository.save(MetadataEntity.builder()
                .seriesEntity(series)
                .language(languageProperties.iso3(tag))
                .title(series.getName())
                .description(description)
                .sourceUri("wikipedia://" + series.getName())
                .build()));
    }

    private void downloadThumbnail(SeriesEntity series, String thumbnailUrl) {
        try {
            imageDownloadService.downloadAndSave(thumbnailUrl, ImageType.COVER, "eng",
                    "wikipedia://" + thumbnailUrl, ImageSave.MediaEntityRef.ofSeries(series));
        } catch (IOException e) {
            log.warn("Failed to download series thumbnail for {}: {}", series.getName(), e.getMessage());
        }
    }
}
