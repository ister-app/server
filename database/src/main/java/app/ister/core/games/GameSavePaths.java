package app.ister.core.games;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Save-location templates of a game, as stored in {@code game_entity.save_paths} /
 * {@code manifest_save_paths}: a JSON array of {@code {"path": "<winAppData>/Game/Saves", "os":
 * "windows"}}. Paths use Ludusavi's placeholders ({@code <base>}, {@code <home>},
 * {@code <winAppData>}, {@code <winLocalAppData>}, {@code <winDocuments>}, {@code <xdgData>},
 * {@code <xdgConfig>}, {@code <storeUserId>}, ...) which the client expands on its own machine;
 * {@code os} is {@code windows}, {@code linux}, {@code mac} or null for any.
 */
public final class GameSavePaths {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<SavePath>> LIST = new TypeReference<>() {};

    private GameSavePaths() {
    }

    public record SavePath(String path, String os) {}

    public static String toJson(List<SavePath> paths) {
        try {
            return MAPPER.writeValueAsString(paths);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize save paths", e);
        }
    }

    /** Parses a stored column; null/blank/invalid gives an empty list rather than failing a request. */
    public static List<SavePath> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, LIST);
        } catch (JsonProcessingException _) {
            return List.of();
        }
    }
}
