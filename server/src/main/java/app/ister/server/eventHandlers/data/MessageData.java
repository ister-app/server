package app.ister.server.eventHandlers.data;

import app.ister.server.enums.EventType;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@NoArgsConstructor(access = AccessLevel.PACKAGE)
@SuperBuilder
@Data
public class MessageData {
    private int version;
    private EventType eventType;
}
