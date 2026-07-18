package app.ister.api.dto;

import app.ister.core.enums.RemoteControlScope;
import app.ister.core.enums.SharingScope;

import java.util.List;
import java.util.UUID;

/** Input for updatePlaybackSharingSettings. The allowlists hold grantee user ids. */
public record PlaybackSharingSettingsInput(
        SharingScope nowPlayingScope,
        RemoteControlScope controlScope,
        List<UUID> nowPlayingAllowedUserIds,
        List<UUID> controlAllowedUserIds) {
}
