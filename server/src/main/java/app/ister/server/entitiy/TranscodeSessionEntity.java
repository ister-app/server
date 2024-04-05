package app.ister.server.entitiy;

import app.ister.server.Transcoder;
import lombok.Data;

import java.util.UUID;

@Data
public class TranscodeSessionEntity {
    private UUID id;
    private String dir;
    private Transcoder transcoder;
}
