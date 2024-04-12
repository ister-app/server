package app.ister.server.nfo;

import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@XmlRootElement(name = "tvshow")
@Getter
public class ShowNfo {
    @XmlElement
    private String title;

    @XmlElement
    private String plot;

    @XmlElement(name="tag")
    private List<String> tags;

    @XmlElement
    @XmlJavaTypeAdapter(DateAdapter.class)
    private LocalDate premiered;
}
