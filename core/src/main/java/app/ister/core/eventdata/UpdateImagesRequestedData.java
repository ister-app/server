package app.ister.core.eventdata;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * Requests a blur-hash sweep over one directory. The sweep is chunked: each message covers at most
 * a configured number of images and, if more remain, publishes a successor carrying {@code afterId}
 * as a keyset cursor. This keeps every message well inside RabbitMQ's {@code consumer_timeout}
 * (30 min by default), which a single full-library sweep exceeded.
 */
@EqualsAndHashCode(callSuper = true)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UpdateImagesRequestedData extends MessageData {
    private UUID directoryEntityId;
    /** Queue suffix of the directory, needed to publish the successor onto the same queue. */
    private String directoryName;
    /** Id of the last image of the previous chunk; {@code null} starts the sweep. */
    private UUID afterId;
}
