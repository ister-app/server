package app.ister.disk.epub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads epub metadata with plain java.util.zip + namespace-aware DOM: container.xml points at the
 * OPF package document, which holds the Dublin Core metadata, the cover manifest item and the
 * media-overlay (SMIL) information. Nothing of the spine/manifest is persisted; the client reader
 * fetches those itself through the epub resource endpoint.
 */
@Component
@Slf4j
public class EpubParser {
    private static final String CONTAINER_ENTRY = "META-INF/container.xml";
    private static final String NS_CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container";
    private static final String NS_OPF = "http://www.idpf.org/2007/opf";
    private static final String NS_DC = "http://purl.org/dc/elements/1.1/";
    private static final String SMIL_MEDIA_TYPE = "application/smil+xml";

    public Optional<EpubInfo> parse(Path epubPath) {
        try (ZipFile zipFile = new ZipFile(epubPath.toFile())) {
            String opfEntryName = findOpfEntryName(zipFile);
            ZipEntry opfEntry = zipFile.getEntry(opfEntryName);
            if (opfEntry == null) {
                log.warn("Epub {} has no OPF entry {}", epubPath, opfEntryName);
                return Optional.empty();
            }
            Document opf = parseXml(zipFile, opfEntry);
            String opfDir = opfEntryName.contains("/")
                    ? opfEntryName.substring(0, opfEntryName.lastIndexOf('/') + 1)
                    : "";
            return Optional.of(new EpubInfo(
                    dcValue(opf, "title"),
                    dcValue(opf, "creator"),
                    dcValue(opf, "language"),
                    dcValue(opf, "description"),
                    parseYear(dcValue(opf, "date")),
                    findCoverEntry(opf, opfDir),
                    hasMediaOverlays(opf),
                    parseTotalDuration(opf)));
        } catch (Exception e) {
            log.warn("Could not parse epub {}: {}", epubPath, e.getMessage());
            return Optional.empty();
        }
    }

    /** Extracts a zip entry (e.g. the cover image) as bytes. */
    public Optional<byte[]> readEntry(Path epubPath, String entryName) {
        try (ZipFile zipFile = new ZipFile(epubPath.toFile())) {
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null) {
                return Optional.empty();
            }
            try (InputStream in = zipFile.getInputStream(entry)) {
                return Optional.of(in.readAllBytes());
            }
        } catch (IOException e) {
            log.warn("Could not read entry {} from epub {}: {}", entryName, epubPath, e.getMessage());
            return Optional.empty();
        }
    }

    private String findOpfEntryName(ZipFile zipFile) throws Exception {
        ZipEntry container = zipFile.getEntry(CONTAINER_ENTRY);
        if (container == null) {
            throw new IllegalArgumentException("missing " + CONTAINER_ENTRY);
        }
        Document doc = parseXml(zipFile, container);
        NodeList rootFiles = doc.getElementsByTagNameNS(NS_CONTAINER, "rootfile");
        if (rootFiles.getLength() == 0) {
            throw new IllegalArgumentException("container.xml has no rootfile");
        }
        return ((Element) rootFiles.item(0)).getAttribute("full-path");
    }

    private Document parseXml(ZipFile zipFile, ZipEntry entry) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Epubs are untrusted input: no external entities or DTDs.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (InputStream in = zipFile.getInputStream(entry)) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private String dcValue(Document opf, String localName) {
        NodeList nodes = opf.getElementsByTagNameNS(NS_DC, localName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.strip();
    }

    private int parseYear(String date) {
        if (date == null || date.length() < 4) {
            return 0;
        }
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    /**
     * The cover is the manifest item with properties~="cover-image" (EPUB 3), falling back to the
     * EPUB 2 convention: a meta[name=cover] whose content is the id of a manifest item.
     */
    private String findCoverEntry(Document opf, String opfDir) {
        NodeList items = opf.getElementsByTagNameNS(NS_OPF, "item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String properties = item.getAttribute("properties");
            if (properties != null && java.util.Arrays.asList(properties.split("\\s+")).contains("cover-image")) {
                return resolve(opfDir, item.getAttribute("href"));
            }
        }
        String coverId = null;
        NodeList metas = opf.getElementsByTagNameNS(NS_OPF, "meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            if ("cover".equals(meta.getAttribute("name"))) {
                coverId = meta.getAttribute("content");
                break;
            }
        }
        if (coverId == null || coverId.isBlank()) {
            return null;
        }
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if (coverId.equals(item.getAttribute("id"))) {
                return resolve(opfDir, item.getAttribute("href"));
            }
        }
        return null;
    }

    /**
     * Media overlays are detected from the epub contents only (never from the filename): a SMIL
     * manifest item or any item carrying a media-overlay attribute.
     */
    private boolean hasMediaOverlays(Document opf) {
        NodeList items = opf.getElementsByTagNameNS(NS_OPF, "item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if (SMIL_MEDIA_TYPE.equals(item.getAttribute("media-type"))
                    || !item.getAttribute("media-overlay").isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** The total media:duration is the meta[property=media:duration] without a refines attribute. */
    private long parseTotalDuration(Document opf) {
        NodeList metas = opf.getElementsByTagNameNS(NS_OPF, "meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            if ("media:duration".equals(meta.getAttribute("property"))
                    && meta.getAttribute("refines").isBlank()) {
                return parseClockValue(meta.getTextContent());
            }
        }
        return 0;
    }

    /** Parses a SMIL clock value ("07:53:38", "123.45s", "1:02:03.500") to milliseconds. */
    static long parseClockValue(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String v = value.strip();
        try {
            if (v.endsWith("ms")) {
                return (long) Double.parseDouble(v.substring(0, v.length() - 2));
            }
            if (v.endsWith("s")) {
                return (long) (Double.parseDouble(v.substring(0, v.length() - 1)) * 1000);
            }
            String[] parts = v.split(":");
            double seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + Double.parseDouble(part);
            }
            return (long) (seconds * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String resolve(String opfDir, String href) {
        return href == null || href.isBlank() ? null : opfDir + href;
    }
}
