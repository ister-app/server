package app.ister.worker.events.tmdbmetadata;

import app.ister.tmdbapi.model.MovieDetails200Response;
import app.ister.tmdbapi.model.TvSeriesDetails200Response;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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

    /**
     * belongs_to_collection is declared as a free-form object in the spec, so the generated model
     * types it as Object; MovieMetadata relies on Jackson deserializing it to a Map with id/name.
     */
    @Test
    void deserializesBelongsToCollectionAsMap() throws Exception {
        String json = "{\"id\":120,\"title\":\"The Fellowship of the Ring\","
                + "\"belongs_to_collection\":{\"id\":119,\"name\":\"The Lord of the Rings Collection\","
                + "\"poster_path\":\"/p.jpg\",\"backdrop_path\":\"/b.jpg\"}}";
        var r = new ObjectMapper().readValue(json, MovieDetails200Response.class);
        Map<?, ?> collection = assertInstanceOf(Map.class, r.getBelongsToCollection());
        assertEquals(119, collection.get("id"));
        assertEquals("The Lord of the Rings Collection", collection.get("name"));
    }
}
