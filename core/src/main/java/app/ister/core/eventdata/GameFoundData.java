package app.ister.core.eventdata;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * A game was created or its files changed: the worker reads what the files say about it
 * (PARAM.SFO title and id, the Steam manifest) and resolves its save locations.
 */
@EqualsAndHashCode(callSuper = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class GameFoundData extends MessageData {
    private UUID gameId;
}
