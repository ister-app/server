package app.ister.disk.nfo;

import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Getter;

@XmlRootElement(name = "artist")
@Getter
public class ArtistNfo {
    @XmlElement
    private String name;

    @XmlElement
    private String sortname;

    @XmlElement
    private String biography;

    @XmlElement
    private String genre;

    @XmlElement
    private String style;

    @XmlElement
    private String mood;

    @XmlElement
    private String born;

    @XmlElement
    private String formed;

    @XmlElement
    private String died;

    @XmlElement
    private String disbanded;

    @XmlElement
    private String yearsactive;
}
