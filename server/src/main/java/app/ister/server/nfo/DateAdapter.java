package app.ister.server.nfo;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;

import java.time.LocalDate;

public class DateAdapter extends XmlAdapter<String, LocalDate> {
//    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public LocalDate unmarshal(String timestampAsString) throws Exception {
        return LocalDate.parse(timestampAsString);
    }

    @Override
    public String marshal(LocalDate localDate) throws Exception {
        return localDate.toString();
    }
}
