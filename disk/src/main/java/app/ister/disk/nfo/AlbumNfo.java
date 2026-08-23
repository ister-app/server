package app.ister.disk.nfo;

import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import lombok.Getter;

import java.time.LocalDate;

@XmlRootElement(name = "album")
@Getter
public class AlbumNfo {
    @XmlElement
    private String title;

    @XmlElement
    private String review;

    @XmlElement
    private String genre;

    @XmlElement
    private String style;

    @XmlElement
    private String mood;

    @XmlElement
    private String label;

    @XmlElement
    private int year;

    @XmlElement
    @XmlJavaTypeAdapter(DateAdapter.class)
    private LocalDate releasedate;

    /**
     * Ister extension, not part of the Kodi album nfo format: BCP-47 tag ("nl") for the language
     * of the nfo's textual metadata. Absent in generator output that doesn't know it.
     */
    @XmlElement
    private String language;

    @XmlElement(name = "set")
    private AlbumSet set;

    /** The album set / book series name, or null when the nfo has no {@code <set>}. */
    public String getSetName() {
        return set == null ? null : set.getName();
    }

    @Getter
    public static class AlbumSet {
        @XmlElement
        private String name;
    }
}
