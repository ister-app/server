package app.ister.core.eventdata;

import app.ister.core.enums.SearchEntityType;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class SearchIndexRequestedData extends MessageData {
    public enum Action {
        UPSERT,
        DELETE,
    }

    private SearchEntityType entityType;
    private UUID entityId;
    private Action action;
}
