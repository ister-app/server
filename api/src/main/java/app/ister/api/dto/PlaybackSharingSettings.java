package app.ister.api.dto;

import app.ister.core.enums.RemoteControlScope;
import app.ister.core.enums.SharingScope;

import java.util.List;
import java.util.UUID;

/** GraphQL view of a user's playback-session sharing preferences. */
public record PlaybackSharingSettings(
        SharingScope nowPlayingScope,
        RemoteControlScope controlScope,
        List<UUID> nowPlayingAllowedUserIds,
        List<UUID> controlAllowedUserIds) {
}
