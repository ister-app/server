package app.ister.core.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaybackSessionSweeperTest {

    @Mock
    private PlaybackSessionRegistry registry;

    @Mock
    private FollowerRegistry followerRegistry;

    @Mock
    private DevicePresenceRegistry devicePresenceRegistry;

    @Mock
    private ServerStatusBroadcaster broadcaster;

    @InjectMocks
    private PlaybackSessionSweeper sweeper;

    @Test
    void broadcastsWhenSessionsExpired() {
        when(registry.removeExpired(PlaybackSessionSweeper.SESSION_TIMEOUT)).thenReturn(true);
        when(followerRegistry.removeExpired(PlaybackSessionSweeper.SESSION_TIMEOUT)).thenReturn(false);
        when(registry.snapshot()).thenReturn(List.of());

        sweeper.sweep();

        verify(broadcaster).emitNowPlaying(List.of());
    }

    @Test
    void broadcastsWhenOnlyFollowersExpired() {
        when(registry.removeExpired(PlaybackSessionSweeper.SESSION_TIMEOUT)).thenReturn(false);
        when(followerRegistry.removeExpired(PlaybackSessionSweeper.SESSION_TIMEOUT)).thenReturn(true);
        when(registry.snapshot()).thenReturn(List.of());

        sweeper.sweep();

        verify(broadcaster).emitNowPlaying(List.of());
    }

    @Test
    void staysQuietWhenNothingExpired() {
        when(registry.removeExpired(PlaybackSessionSweeper.SESSION_TIMEOUT)).thenReturn(false);
        when(followerRegistry.removeExpired(PlaybackSessionSweeper.SESSION_TIMEOUT)).thenReturn(false);

        sweeper.sweep();

        verifyNoInteractions(broadcaster);
    }

    @Test
    void sweepsDevicePresenceWithoutBroadcasting() {
        // Device online state is pulled via the myDevices query — presence expiry alone
        // must not re-emit the now-playing list.
        when(registry.removeExpired(PlaybackSessionSweeper.SESSION_TIMEOUT)).thenReturn(false);
        when(followerRegistry.removeExpired(PlaybackSessionSweeper.SESSION_TIMEOUT)).thenReturn(false);
        when(devicePresenceRegistry.removeExpired(PlaybackSessionSweeper.SESSION_TIMEOUT)).thenReturn(true);

        sweeper.sweep();

        verify(devicePresenceRegistry).removeExpired(PlaybackSessionSweeper.SESSION_TIMEOUT);
        verifyNoInteractions(broadcaster);
    }
}
