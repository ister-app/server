package app.ister.core.service;

import app.ister.core.entity.UserSharingSettingsEntity;
import app.ister.core.enums.RemoteControlScope;
import app.ister.core.enums.SharingCapability;
import app.ister.core.enums.SharingScope;
import app.ister.core.repository.UserSharingGrantRepository;
import app.ister.core.repository.UserSharingSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaybackSharingServiceTest {

    @Mock
    private UserSharingSettingsRepository settingsRepository;

    @Mock
    private UserSharingGrantRepository grantRepository;

    private PlaybackSharingService subject;

    private final UUID owner = UUID.randomUUID();
    private final UUID viewer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        subject = new PlaybackSharingService(settingsRepository, grantRepository);
        lenient().when(grantRepository.findGranteeIdsByOwnerAndCapability(eq(owner), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
    }

    private void settings(SharingScope nowPlaying, RemoteControlScope control) {
        lenient().when(settingsRepository.findByUserEntityId(owner)).thenReturn(Optional.of(
                UserSharingSettingsEntity.builder().nowPlayingScope(nowPlaying).controlScope(control).build()));
    }

    // --- owner always ---

    @Test
    void ownerAlwaysSeesAndControlsOwnSessionEvenWhenPrivate() {
        settings(SharingScope.PRIVATE, RemoteControlScope.PRIVATE);
        assertTrue(subject.canView(owner, owner));
        assertTrue(subject.canControl(owner, owner, null, Set.of()));
    }

    // --- now-playing visibility ---

    @Test
    void defaultsShareNowPlayingWithEveryoneAndKeepControlPrivate() {
        when(settingsRepository.findByUserEntityId(owner)).thenReturn(Optional.empty());
        assertTrue(subject.canView(viewer, owner));
        assertFalse(subject.canControl(viewer, owner, null, Set.of()));
    }

    @Test
    void privateNowPlayingHidesFromOthers() {
        settings(SharingScope.PRIVATE, RemoteControlScope.PRIVATE);
        assertFalse(subject.canView(viewer, owner));
    }

    @Test
    void allowlistNowPlayingOnlyShowsToGrantees() {
        settings(SharingScope.ALLOWLIST, RemoteControlScope.PRIVATE);
        when(grantRepository.findGranteeIdsByOwnerAndCapability(owner, SharingCapability.VIEW))
                .thenReturn(List.of(viewer));
        assertTrue(subject.canView(viewer, owner));
        assertFalse(subject.canView(UUID.randomUUID(), owner));
    }

    // --- remote control ---

    @Test
    void everyoneControlScopeLetsAnyoneControl() {
        settings(SharingScope.PRIVATE, RemoteControlScope.EVERYONE);
        assertTrue(subject.canControl(viewer, owner, null, Set.of()));
    }

    @Test
    void allowlistControlScopeUsesAccountControlGrants() {
        settings(SharingScope.PRIVATE, RemoteControlScope.ALLOWLIST);
        when(grantRepository.findGranteeIdsByOwnerAndCapability(owner, SharingCapability.CONTROL))
                .thenReturn(List.of(viewer));
        assertTrue(subject.canControl(viewer, owner, null, Set.of()));
        assertFalse(subject.canControl(UUID.randomUUID(), owner, null, Set.of()));
    }

    @Test
    void sameAsNowPlayingControlScopeDelegatesToVisibility() {
        settings(SharingScope.ALLOWLIST, RemoteControlScope.SAME_AS_NOW_PLAYING);
        when(grantRepository.findGranteeIdsByOwnerAndCapability(owner, SharingCapability.VIEW))
                .thenReturn(List.of(viewer));
        assertTrue(subject.canControl(viewer, owner, null, Set.of()));
        assertFalse(subject.canControl(UUID.randomUUID(), owner, null, Set.of()));
    }

    // --- per-session override ---

    @Test
    void perSessionOverrideBeatsAccountDefault() {
        settings(SharingScope.PRIVATE, RemoteControlScope.PRIVATE);
        assertTrue(subject.canControl(viewer, owner, RemoteControlScope.EVERYONE, Set.of()));
    }

    @Test
    void perSessionAllowlistOverrideUsesTheSessionsOwnList() {
        settings(SharingScope.PRIVATE, RemoteControlScope.EVERYONE);
        // The override list, not the account EVERYONE default, decides.
        assertTrue(subject.canControl(viewer, owner, RemoteControlScope.ALLOWLIST, Set.of(viewer)));
        assertFalse(subject.canControl(viewer, owner, RemoteControlScope.ALLOWLIST, Set.of()));
    }

    // --- caching ---

    @Test
    void ownerConfigIsCachedUntilInvalidated() {
        settings(SharingScope.EVERYONE, RemoteControlScope.PRIVATE);
        subject.canView(viewer, owner);
        subject.canView(viewer, owner);
        verify(settingsRepository, times(1)).findByUserEntityId(owner);

        subject.invalidateCache();
        subject.canView(viewer, owner);
        verify(settingsRepository, times(2)).findByUserEntityId(owner);
    }
}
