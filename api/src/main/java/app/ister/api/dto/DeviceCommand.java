package app.ister.api.dto;

import app.ister.core.enums.DeviceCommandType;
import app.ister.core.enums.MediaType;
import app.ister.core.eventdata.DeviceCommandData;

import java.util.UUID;

/** GraphQL view of one device-targeted command (see DeviceCommandService in core). */
public record DeviceCommand(
        UUID deviceId,
        DeviceCommandType command,
        MediaType mediaType,
        UUID mediaId,
        UUID startId,
        UUID playQueueId,
        Double positionInMilliseconds,
        String timestamp) {

    public static DeviceCommand from(DeviceCommandData data) {
        return new DeviceCommand(data.getDeviceId(), data.getCommand(), data.getMediaType(),
                data.getMediaId(), data.getStartId(), data.getPlayQueueId(),
                data.getPositionInMilliseconds() == null ? null : data.getPositionInMilliseconds().doubleValue(),
                String.valueOf(data.getTimestamp()));
    }
}
