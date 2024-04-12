package app.ister.server.eventHandlers.TMDBMetadata;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Builder
@Getter
@Setter
public class TMDBResult {
    String language;
    String title;
    LocalDate released;
    String sourceUri;
    String description;
    String posterUrl;
    String backgroundUrl;
}
