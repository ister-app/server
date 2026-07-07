package app.ister.worker.events.tmdbmetadata;

import app.ister.tmdbapi.model.TvSeriesDetails200Response;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TmdbModelDeserializationTest {
    @Test
    void deserializesPosterAndBackdropPath() throws Exception {
        String json = "{\"id\":1405,\"name\":\"Dexter\",\"overview\":\"o\","
                + "\"poster_path\":\"/q8dWfc4JwQuv3HayIZeO84jAXED.jpg\","
                + "\"backdrop_path\":\"/aSGSxGMTP893DPMCvMl9AdnEICE.jpg\"}";
        var r = new ObjectMapper().readValue(json, TvSeriesDetails200Response.class);
        assertEquals("Dexter", r.getName());
        assertEquals("o", r.getOverview());
        assertEquals("/q8dWfc4JwQuv3HayIZeO84jAXED.jpg", r.getPosterPath());
        assertEquals("/aSGSxGMTP893DPMCvMl9AdnEICE.jpg", r.getBackdropPath());
    }
}
