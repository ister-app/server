package app.ister.server.nfo;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.*;

class DateAdapterTest {

    @Test
    void unmarshal() throws Exception {
        DateAdapter subject = new DateAdapter();
        assertEquals(Timestamp.valueOf("2017-09-24 00:00:00.0"), subject.unmarshal("2017-09-24"));
    }

    @Test
    void marshal() throws Exception {
        DateAdapter subject = new DateAdapter();
        assertEquals("2017-09-24", subject.marshal(Timestamp.valueOf("2017-09-24 00:00:00.0")));
    }

}