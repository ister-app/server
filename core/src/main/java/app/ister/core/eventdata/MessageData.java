package app.ister.core.eventdata;

import app.ister.core.enums.EventType;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder
@Data
public class MessageData {
    private int version;
    private EventType eventType;
}
