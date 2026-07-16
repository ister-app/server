package app.ister.disk.epub;

/**
 * Metadata read from an epub's OPF package document.
 *
 * @param title          dc:title, or null
 * @param author         dc:creator, or null
 * @param language       dc:language (BCP 47 / ISO-639-1 tag as written in the epub), or null
 * @param description    dc:description, or null
 * @param releaseYear    year of dc:date, or 0 when unknown
 * @param isbn           ISBN from dc:identifier, normalized to bare ISBN-10/13, or null
 * @param seriesName     series from EPUB 3 belongs-to-collection or calibre:series, or null
 * @param seriesIndex    position within the series (group-position / calibre:series_index), or null
 * @param coverEntry     zip entry name of the cover image, or null
 * @param mediaOverlays  true when the epub carries EPUB 3 media overlays (SMIL read-aloud audio)
 * @param durationInMilliseconds total media:duration of the overlays, or 0
 */
public record EpubInfo(
        String title,
        String author,
        String language,
        String description,
        int releaseYear,
        String isbn,
        String seriesName,
        Double seriesIndex,
        String coverEntry,
        boolean mediaOverlays,
        long durationInMilliseconds) {
}
