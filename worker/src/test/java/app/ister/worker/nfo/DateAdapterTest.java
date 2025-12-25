package app.ister.worker.nfo;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DateAdapterTest {

    @Test
    void unmarshal() throws Exception {
        DateAdapter subject = new DateAdapter();
        assertEquals(LocalDate.parse("2017-09-24"), subject.unmarshal("2017-09-24"));
    }

    @Test
    void marshal() throws Exception {
        DateAdapter subject = new DateAdapter();
        assertEquals("2017-09-24", subject.marshal(LocalDate.parse("2017-09-24")));
    }

}