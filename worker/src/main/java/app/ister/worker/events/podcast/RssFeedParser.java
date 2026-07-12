package app.ister.worker.events.podcast;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fetches and parses podcast RSS feeds (RSS 2.0 + the iTunes namespace). Feeds are untrusted
 * input: no DTDs or external entities, item cap, and hardened date/duration parsing — real-world
 * feeds are messy. Uses conditional GETs (ETag/Last-Modified) so an unchanged feed costs nothing.
 */
@Slf4j
@Component
public class RssFeedParser {
    private static final String NS_ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd";
    private static final String USER_AGENT = "IsterServer/1.0 (info@ister.app)";
    /** Cap on parsed items per refresh; some feeds carry their entire multi-year archive. */
    private static final int MAX_ITEMS = 500;

    private final RestClient restClient;

    public RssFeedParser() {
        this.restClient = RestClient.builder()
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
    }

    /** Channel + items; {@code notModified} = the conditional GET returned 304. */
    public record Feed(boolean notModified, String etag, String lastModified,
                       Channel channel, List<Item> items) {
        static Feed notModifiedResult() {
            return new Feed(true, null, null, null, List.of());
        }
    }

    public record Channel(String title, String description, String language, String author, String imageUrl) {
    }

    public record Item(String guid, String title, String description, Instant publishedAt,
                       String enclosureUrl, String enclosureType, long durationInMilliseconds,
                       Integer episodeNumber, Integer seasonNumber, String imageUrl) {
    }

    public Optional<Feed> fetch(String feedUrl, String etag, String lastModified) {
        try {
            ResponseEntity<byte[]> response = restClient.get()
                    .uri(feedUrl)
                    .headers(headers -> {
                        if (etag != null) headers.set(HttpHeaders.IF_NONE_MATCH, etag);
                        if (lastModified != null) headers.set(HttpHeaders.IF_MODIFIED_SINCE, lastModified);
                    })
                    .retrieve()
                    .toEntity(byte[].class);
            if (response.getStatusCode().value() == 304) {
                return Optional.of(Feed.notModifiedResult());
            }
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                return Optional.empty();
            }
            Feed parsed = parse(body);
            return Optional.of(new Feed(false,
                    response.getHeaders().getFirst(HttpHeaders.ETAG),
                    response.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED),
                    parsed.channel(), parsed.items()));
        } catch (RestClientException e) {
            log.warn("Could not fetch feed {}: {}", feedUrl, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not parse feed {}: {}", feedUrl, e.getMessage());
            return Optional.empty();
        }
    }

    Feed parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

        Element channelElement = firstElement(doc.getDocumentElement(), "channel");
        if (channelElement == null) {
            throw new IllegalArgumentException("feed has no <channel>");
        }
        Channel channel = new Channel(
                directChildText(channelElement, "title"),
                Optional.ofNullable(directChildText(channelElement, "description"))
                        .orElse(itunesText(channelElement, "summary")),
                directChildText(channelElement, "language"),
                Optional.ofNullable(itunesText(channelElement, "author"))
                        .orElse(directChildText(channelElement, "author")),
                channelImageUrl(channelElement));

        List<Item> items = new ArrayList<>();
        NodeList itemNodes = channelElement.getElementsByTagName("item");
        for (int i = 0; i < itemNodes.getLength() && items.size() < MAX_ITEMS; i++) {
            Element item = (Element) itemNodes.item(i);
            Element enclosure = firstElement(item, "enclosure");
            String enclosureUrl = enclosure != null ? enclosure.getAttribute("url") : null;
            if (enclosureUrl == null || enclosureUrl.isBlank()) {
                continue; // not a playable episode (e.g. a news item)
            }
            String guid = Optional.ofNullable(directChildText(item, "guid")).orElse(enclosureUrl);
            items.add(new Item(
                    guid,
                    directChildText(item, "title"),
                    Optional.ofNullable(directChildText(item, "description"))
                            .orElse(itunesText(item, "summary")),
                    parsePubDate(directChildText(item, "pubDate")),
                    enclosureUrl,
                    enclosure.getAttribute("type"),
                    parseItunesDuration(itunesText(item, "duration")),
                    parseInteger(itunesText(item, "episode")),
                    parseInteger(itunesText(item, "season")),
                    itunesImageHref(item)));
        }
        if (itemNodes.getLength() > MAX_ITEMS) {
            log.info("Feed has {} items; only the newest {} are processed", itemNodes.getLength(), MAX_ITEMS);
        }
        return new Feed(false, null, null, channel, items);
    }

    /** itunes:image has an href attribute; plain <image><url> is the RSS 2.0 fallback. */
    private String channelImageUrl(Element channel) {
        String itunesImage = itunesImageHref(channel);
        if (itunesImage != null) {
            return itunesImage;
        }
        Element image = firstElement(channel, "image");
        return image != null ? directChildText(image, "url") : null;
    }

    private String itunesImageHref(Element parent) {
        NodeList images = parent.getElementsByTagNameNS(NS_ITUNES, "image");
        for (int i = 0; i < images.getLength(); i++) {
            if (images.item(i).getParentNode() == parent) {
                String href = ((Element) images.item(i)).getAttribute("href");
                return href.isBlank() ? null : href;
            }
        }
        return null;
    }

    /** Text of a direct child element (namespace-less), so channel fields don't match item fields. */
    private String directChildText(Element parent, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element
                    && localName.equals(element.getLocalName())
                    && element.getNamespaceURI() == null) {
                String text = element.getTextContent();
                return text == null || text.isBlank() ? null : text.strip();
            }
        }
        return null;
    }

    private String itunesText(Element parent, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element
                    && localName.equals(element.getLocalName())
                    && NS_ITUNES.equals(element.getNamespaceURI())) {
                String text = element.getTextContent();
                return text == null || text.isBlank() ? null : text.strip();
            }
        }
        return null;
    }

    private Element firstElement(Element parent, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    /** RFC 1123 pubDates, with a lenient fallback for the common "+0000"-style offsets. */
    static Instant parsePubDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception _) {
            // Not RFC 1123 (some feeds emit ISO dates); try ISO before giving up.
        }
        try {
            return Instant.parse(value.strip());
        } catch (Exception _) {
            return null;
        }
    }

    /** itunes:duration: "HH:MM:SS", "MM:SS" or plain seconds. */
    static long parseItunesDuration(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            String[] parts = value.strip().split(":");
            long seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + (long) Double.parseDouble(part);
            }
            return seconds * 1000;
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /** Exposed for tests. */
    Feed parseString(String xml) throws Exception {
        return parse(xml.getBytes(StandardCharsets.UTF_8));
    }
}
