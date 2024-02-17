package app.ister.server.scanner;

import lombok.Data;

import java.nio.file.Path;

@Data
public class AnalyzedObject {
    private Path path;
    private AnalyzedType analyzedType;

    public AnalyzedObject(Path path, AnalyzedType analyzedType) {
        this.path = path;
        this.analyzedType = analyzedType;
    }

    public enum AnalyzedType {
        SHOW,
        SEASON,
        EPISODE
    }
}
