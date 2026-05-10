package app.ister.disk.nfo;

import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import lombok.Getter;

import java.time.LocalDate;

@XmlRootElement(name = "movie")
@Getter
public class MovieNfo {
    @XmlElement
    private String title;

    @XmlElement
    private String plot;

    @XmlElement
    @XmlJavaTypeAdapter(DateAdapter.class)
    private LocalDate premiered;
}
