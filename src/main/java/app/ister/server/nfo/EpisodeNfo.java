package app.ister.server.nfo;

import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import lombok.Getter;

import java.sql.Timestamp;
import java.util.List;

@XmlRootElement(name = "episodedetails")
@Getter
public class EpisodeNfo {
    @XmlElement
    private String title;

    @XmlElement
    private String plot;

    @XmlElement
    @XmlJavaTypeAdapter(DateAdapter.class)
    private Timestamp aired;
}
