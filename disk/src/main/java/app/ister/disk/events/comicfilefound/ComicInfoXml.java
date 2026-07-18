package app.ister.disk.events.comicfilefound;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.Optional;

/**
 * The ComicInfo.xml embedded metadata standard (ComicTagger/ComicRack) found inside cbz archives.
 * Only the fields the pipeline uses are parsed.
 *
 * @param series  the Series element, or null
 * @param number  the Number element parsed as a double (calibre-style fractions allowed), or null
 * @param title   the Title element, or null
 * @param summary the Summary element, or null
 * @param year    the Year element, or 0 when absent/unparseable
 * @param writer  the Writer element, or null
 * @param manga   the Manga element ({@code No}/{@code Yes}/{@code YesAndRightToLeft}), or null
 */
public record ComicInfoXml(String series, Double number, String title, String summary, int year, String writer,
                           String manga) {

    /**
     * Whether the volume reads right-to-left. Per the ComicRack spec only {@code YesAndRightToLeft}
     * means RTL; plain {@code Yes} is manga published flipped, reading LTR.
     */
    public boolean rightToLeft() {
        return "YesAndRightToLeft".equalsIgnoreCase(manga);
    }

    /** Parses a ComicInfo.xml stream; empty on any parse failure (untrusted input). */
    public static Optional<ComicInfoXml> parse(InputStream in) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Comic archives are untrusted input: no external entities or DTDs.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document doc = factory.newDocumentBuilder().parse(in);
            return Optional.of(new ComicInfoXml(
                    text(doc, "Series"),
                    parseNumber(text(doc, "Number")),
                    text(doc, "Title"),
                    text(doc, "Summary"),
                    parseYear(text(doc, "Year")),
                    text(doc, "Writer"),
                    text(doc, "Manga")));
        } catch (Exception _) {
            return Optional.empty();
        }
    }

    private static String text(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Double parseNumber(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static int parseYear(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException _) {
            return 0;
        }
    }
}
