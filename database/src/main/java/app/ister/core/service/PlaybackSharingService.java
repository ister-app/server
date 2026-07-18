package app.ister.core.service;

import app.ister.core.enums.RemoteControlScope;
import app.ister.core.enums.SharingCapability;
import app.ister.core.enums.SharingScope;
import app.ister.core.repository.UserSharingGrantRepository;
import app.ister.core.repository.UserSharingSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides who may see and who may remote-control a user's playback sessions.
 *
 * <p>Now-playing visibility defaults to {@link SharingScope#EVERYONE} (preserving the original
 * all-sessions-visible behaviour); remote control defaults to {@link RemoteControlScope#PRIVATE}
 * (owner only). Both are configurable per user, with an explicit allowlist per capability, and the
 * remote-control decision can be overridden per session (an override enum plus that session's own
 * control allowlist, carried alongside the session so this decision never re-hits the play queue).
 *
 * <p>Per-owner sharing config is cached for a few seconds, mirroring {@link LibraryAccessService}:
 * the now-playing subscription filter would otherwise hit the database once per session per emission.
 * {@code updatePlaybackSharingSettings} invalidates the cache, so a change takes effect within
 * {@link #CACHE_TTL} on other nodes at worst.
 *
 * <p>The owner always sees and controls their own sessions; a denied viewer is treated as if the
 * session did not exist (it is filtered out / the command is dropped), never a 403 — matching the
 * project's deny-as-not-found convention.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PlaybackSharingService {
    private static final Duration CACHE_TTL = Duration.ofSeconds(15);

    private final UserSharingSettingsRepository settingsRepository;
    private final UserSharingGrantRepository grantRepository;

    /** A user's effective sharing config: the two scopes plus the resolved allowlists. */
    public record OwnerSharing(SharingScope nowPlayingScope, RemoteControlScope controlScope,
                               Set<UUID> viewAllowed, Set<UUID> controlAllowed) {
    }

    private record CacheEntry(OwnerSharing sharing, Instant expiresAt) {
    }

    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Cached per-owner sharing config; falls back to the defaults when the user saved nothing. */
    public OwnerSharing forOwner(UUID ownerId) {
        CacheEntry entry = cache.get(ownerId);
        if (entry != null && entry.expiresAt().isAfter(Instant.now())) {
            return entry.sharing();
        }
        OwnerSharing sharing = compute(ownerId);
        cache.put(ownerId, new CacheEntry(sharing, Instant.now().plus(CACHE_TTL)));
        return sharing;
    }

    private OwnerSharing compute(UUID ownerId) {
        SharingScope nowPlayingScope = SharingScope.EVERYONE;
        RemoteControlScope controlScope = RemoteControlScope.PRIVATE;
        var settings = settingsRepository.findByUserEntityId(ownerId);
        if (settings.isPresent()) {
            nowPlayingScope = settings.get().getNowPlayingScope();
            controlScope = settings.get().getControlScope();
        }
        Set<UUID> viewAllowed = new HashSet<>(grantRepository.findGranteeIdsByOwnerAndCapability(ownerId, SharingCapability.VIEW));
        Set<UUID> controlAllowed = new HashSet<>(grantRepository.findGranteeIdsByOwnerAndCapability(ownerId, SharingCapability.CONTROL));
        return new OwnerSharing(nowPlayingScope, controlScope, viewAllowed, controlAllowed);
    }

    /** Whether {@code viewerId} may see {@code ownerId}'s now-playing sessions. */
    public boolean canView(UUID viewerId, UUID ownerId) {
        if (viewerId == null || ownerId == null) {
            return false;
        }
        if (viewerId.equals(ownerId)) {
            return true;
        }
        return viewAllowedBy(viewerId, forOwner(ownerId));
    }

    /**
     * Whether {@code viewerId} may remote-control {@code ownerId}'s session.
     *
     * @param override      the session's per-session override, or null to use the account default
     * @param sessionAllowed the session's own control allowlist (used only when the effective scope
     *                       is an ALLOWLIST that came from the override)
     */
    public boolean canControl(UUID viewerId, UUID ownerId, RemoteControlScope override, Set<UUID> sessionAllowed) {
        if (viewerId == null || ownerId == null) {
            return false;
        }
        if (viewerId.equals(ownerId)) {
            return true;
        }
        OwnerSharing sharing = forOwner(ownerId);
        RemoteControlScope effective = override != null ? override : sharing.controlScope();
        return switch (effective) {
            case PRIVATE -> false;
            case EVERYONE -> true;
            case ALLOWLIST -> override != null
                    ? sessionAllowed != null && sessionAllowed.contains(viewerId)
                    : sharing.controlAllowed().contains(viewerId);
            case SAME_AS_NOW_PLAYING -> viewAllowedBy(viewerId, sharing);
        };
    }

    private boolean viewAllowedBy(UUID viewerId, OwnerSharing sharing) {
        return switch (sharing.nowPlayingScope()) {
            case EVERYONE -> true;
            case PRIVATE -> false;
            case ALLOWLIST -> sharing.viewAllowed().contains(viewerId);
        };
    }

    /** Called by the sharing mutations so a scope or allowlist change applies immediately. */
    public void invalidateCache() {
        cache.clear();
    }
}
