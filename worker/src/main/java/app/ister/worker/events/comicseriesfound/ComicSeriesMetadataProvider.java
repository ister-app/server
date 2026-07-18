package app.ister.worker.events.comicseriesfound;

import app.ister.worker.events.wikipedia.WikipediaService;

import java.util.List;

/**
 * A source of comic series metadata, looked up by series name. Wikipedia/Wikidata is the only
 * implementation today; ComicVine, Metron or MangaDex can slot in behind the same call later.
 */
public interface ComicSeriesMetadataProvider {

    /**
     * Per-language descriptions plus a thumbnail, and whether the source types the series as
     * manga; {@code SeriesContent.EMPTY} when nothing matched.
     */
    WikipediaService.SeriesContent fetchSeriesContent(String seriesName, List<String> languageTags);
}
