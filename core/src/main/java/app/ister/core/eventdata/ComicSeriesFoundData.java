package app.ister.core.eventdata;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/** A new comic series: the worker enriches it with a Wikipedia description and thumbnail. */
@EqualsAndHashCode(callSuper = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class ComicSeriesFoundData extends MessageData {
    private UUID seriesId;
}
