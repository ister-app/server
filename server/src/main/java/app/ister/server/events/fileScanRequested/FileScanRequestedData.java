package app.ister.server.events.fileScanRequested;

import app.ister.server.events.MessageData;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.nio.file.Path;
import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class FileScanRequestedData extends MessageData {
    private Path path;
    private boolean regularFile;
    private long size;
    private UUID directoryEntityUUID;
}