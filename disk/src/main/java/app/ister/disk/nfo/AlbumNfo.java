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
}
