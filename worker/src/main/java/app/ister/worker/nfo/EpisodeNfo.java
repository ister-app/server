package app.ister.worker.nfo;

import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import lombok.Getter;

import java.time.LocalDate;

@XmlRootElement(name = "episodedetails")
@Getter
public class EpisodeNfo {
    @XmlElement
    private String title;

    @XmlElement
    private String plot;

    @XmlElement
    @XmlJavaTypeAdapter(DateAdapter.class)
    private LocalDate aired;
}
