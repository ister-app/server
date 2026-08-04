package app.ister.core.status;

import app.ister.core.eventdata.DeviceCommandData;
import app.ister.core.eventdata.DevicePresenceData;
import app.ister.core.service.MessageSender;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes device-targeted commands and device presence heartbeats on the status exchange.
 * Called from the device mutations on the API request thread; only plain values cross into
 * the message.
 */
@Service
public class DeviceCommandService {

    private final MessageSender messageSender;

    public DeviceCommandService(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    public void publish(DeviceCommandData command) {
        command.setTimestamp(Instant.now());
        messageSender.sendStatus(command);
    }

    public void publishPresence(UUID ownerUserId, UUID deviceId) {
        messageSender.sendStatus(DevicePresenceData.builder()
                .ownerUserId(ownerUserId)
                .deviceId(deviceId)
                .timestamp(Instant.now())
                .build());
    }
}
