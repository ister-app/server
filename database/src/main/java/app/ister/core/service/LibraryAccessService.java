package app.ister.core.service;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserLibraryAccessEntity;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.UserLibraryAccessRepository;
import app.ister.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides which libraries the calling user may see: every library for admins, otherwise the
 * visible-to-all libraries plus the user's explicit {@link UserLibraryAccessEntity} grants.
 * Works for both JWT-authenticated GraphQL requests and stream-token REST requests (those carry
 * no JWT, so the admin bypass falls back to the snapshot on the user row).
 *
 * <p>Results are cached per user for a few seconds so a single page render doesn't hit the
 * database once per resolver; the management mutations invalidate the cache, so a revocation
 * takes effect within {@link #CACHE_TTL} on other nodes' caches at worst.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LibraryAccessService {
    private static final Duration CACHE_TTL = Duration.ofSeconds(15);

    private final UserRepository userRepository;
    private final UserLibraryAccessRepository userLibraryAccessRepository;
    private final LibraryRepository libraryRepository;

    private record CacheEntry(Optional<Set<UUID>> allowedIds, Instant expiresAt) {
    }

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** True when the caller holds the admin role (JWT authority, or the DB snapshot for stream tokens). */
    public boolean isAdmin(Authentication authentication) {
        if (UserService.hasAdminRole(authentication)) {
            return true;
        }
        if (authentication.getPrincipal() instanceof Jwt) {
            return false;
        }
        return userRepository.findByExternalId(authentication.getName())
                .map(user -> user.isAdmin())
                .orElse(false);
    }

    /**
     * The library ids the caller may see, or {@link Optional#empty()} for "all of them" (admin).
     * The empty-optional sentinel lets admin queries keep their unfiltered findAll path.
     */
    public Optional<Set<UUID>> allowedLibraryIds(Authentication authentication) {
        CacheEntry entry = cache.get(authentication.getName());
        if (entry != null && entry.expiresAt().isAfter(Instant.now())) {
            return entry.allowedIds();
        }
        Optional<Set<UUID>> allowedIds = computeAllowedIds(authentication);
        cache.put(authentication.getName(), new CacheEntry(allowedIds, Instant.now().plus(CACHE_TTL)));
        return allowedIds;
    }

    /**
     * Like {@link #allowedLibraryIds(Authentication)}, for flows that only have the owning user
     * row at hand (e.g. lazy play-queue extension). The admin bypass uses the snapshot on the
     * user row, matching the stream-token path of {@link #isAdmin(Authentication)}.
     */
    public Optional<Set<UUID>> allowedLibraryIdsForUser(UserEntity user) {
        CacheEntry entry = cache.get(user.getExternalId());
        if (entry != null && entry.expiresAt().isAfter(Instant.now())) {
            return entry.allowedIds();
        }
        Optional<Set<UUID>> allowedIds = computeAllowedIdsForUser(user);
        cache.put(user.getExternalId(), new CacheEntry(allowedIds, Instant.now().plus(CACHE_TTL)));
        return allowedIds;
    }

    public boolean canAccess(UUID libraryId, Authentication authentication) {
        if (libraryId == null) {
            return false;
        }
        return allowedLibraryIds(authentication)
                .map(allowed -> allowed.contains(libraryId))
                .orElse(true);
    }

    public boolean canAccess(LibraryEntity libraryEntity, Authentication authentication) {
        return libraryEntity != null && canAccess(libraryEntity.getId(), authentication);
    }

    /** Called by the management mutations so grants and visibility changes apply immediately. */
    public void invalidateCache() {
        cache.clear();
    }

    private Optional<Set<UUID>> computeAllowedIds(Authentication authentication) {
        if (isAdmin(authentication)) {
            return Optional.empty();
        }
        return Optional.of(grantedLibraryIds(authentication.getName()));
    }

    private Optional<Set<UUID>> computeAllowedIdsForUser(UserEntity user) {
        if (user.isAdmin()) {
            return Optional.empty();
        }
        return Optional.of(grantedLibraryIds(user.getExternalId()));
    }

    private Set<UUID> grantedLibraryIds(String externalId) {
        Set<UUID> allowed = new HashSet<>();
        libraryRepository.findByVisibleToAllTrue().forEach(library -> allowed.add(library.getId()));
        userLibraryAccessRepository.findByUserEntityExternalId(externalId)
                .forEach(access -> allowed.add(access.getLibraryEntity().getId()));
        return allowed;
    }
}
