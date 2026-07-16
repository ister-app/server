package app.ister.core.eventdata;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/** A cbz or pdf comic volume file found by the scanner; epub volumes go through EPUB_FILE_FOUND. */
@EqualsAndHashCode(callSuper = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class ComicFileFoundData extends MessageData {
    private UUID directoryEntityUUID;
    private UUID bookEntityUUID;
    private UUID mediaFileEntityUUID;
    private String path;
}
