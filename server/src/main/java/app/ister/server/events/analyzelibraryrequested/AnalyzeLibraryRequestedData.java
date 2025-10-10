package app.ister.server.events.analyzelibraryrequested;

import app.ister.server.events.MessageData;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class AnalyzeLibraryRequestedData extends MessageData {
}
