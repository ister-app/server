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
    private ServerStatusBroadcaster broadcaster;

    @InjectMocks
    private PlaybackSessionSweeper sweeper;

    @Test
    void broadcastsWhenSessionsExpired() {
        when(registry.removeExpired(PlaybackSessionSweeper.SESSION_TIMEOUT)).thenReturn(true);
        when(registry.snapshot()).thenReturn(List.of());

        sweeper.sweep();

        verify(broadcaster).emitNowPlaying(List.of());
    }

    @Test
    void staysQuietWhenNothingExpired() {
        when(registry.removeExpired(PlaybackSessionSweeper.SESSION_TIMEOUT)).thenReturn(false);

        sweeper.sweep();

        verifyNoInteractions(broadcaster);
    }
}
