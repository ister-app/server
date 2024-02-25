package app.ister.server.nfo;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DateAdapter extends XmlAdapter<String, Timestamp> {
//    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public Timestamp unmarshal(String timestampAsString) throws Exception {
        return Timestamp.from(new SimpleDateFormat("yyyy-MM-dd").parse(timestampAsString).toInstant());
    }

    @Override
    public String marshal(Timestamp timestamp) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd").format(timestamp);
    }
}
