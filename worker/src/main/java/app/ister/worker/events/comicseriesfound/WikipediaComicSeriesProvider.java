package app.ister.worker.events.comicseriesfound;

import app.ister.worker.events.wikipedia.WikipediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class WikipediaComicSeriesProvider implements ComicSeriesMetadataProvider {

    private final WikipediaService wikipediaService;

    @Override
    public WikipediaService.SeriesContent fetchSeriesContent(String seriesName, List<String> languageTags) {
        return wikipediaService.fetchContentForSeries(seriesName, languageTags);
    }
}
