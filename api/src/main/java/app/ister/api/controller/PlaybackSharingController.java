package app.ister.api.controller;

import app.ister.api.dto.PlaybackSharingSettings;
import app.ister.api.dto.PlaybackSharingSettingsInput;
import app.ister.api.dto.ShareableUser;
import app.ister.core.entity.PlayQueueControlGrantEntity;
import app.ister.core.entity.PlayQueueEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserSharingGrantEntity;
import app.ister.core.entity.UserSharingSettingsEntity;
import app.ister.core.enums.RemoteControlScope;
import app.ister.core.enums.SharingCapability;
import app.ister.core.repository.PlayQueueControlGrantRepository;
import app.ister.core.repository.PlayQueueRepository;
import app.ister.core.repository.UserRepository;
import app.ister.core.repository.UserSharingGrantRepository;
import app.ister.core.repository.UserSharingSettingsRepository;
import app.ister.core.service.PlaybackSharingService;
import app.ister.core.service.PlaybackSharingService.OwnerSharing;
import app.ister.core.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Playback-session sharing: who may see (now-playing) and who may remote-control the calling user's
 * sessions. The account-level defaults and per-capability allowlists live in
 * {@code user_sharing_settings} / {@code user_sharing_grant}; a per-session remote-control override
 * lives on the play queue ({@code setSessionSharing}). Enforcement is in {@link PlaybackSharingService}.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class PlaybackSharingController {

    private final UserService userService;
    private final PlaybackSharingService playbackSharingService;
    private final UserSharingSettingsRepository settingsRepository;
    private final UserSharingGrantRepository grantRepository;
    private final PlayQueueControlGrantRepository playQueueControlGrantRepository;
    private final PlayQueueRepository playQueueRepository;
    private final UserRepository userRepository;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public PlaybackSharingSettings playbackSharingSettings(Authentication authentication) {
        UUID userId = userService.getOrCreateUser(authentication).getId();
        return toDto(playbackSharingService.forOwner(userId));
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<ShareableUser> shareableUsers(Authentication authentication) {
        UUID callerId = userService.getOrCreateUser(authentication).getId();
        List<ShareableUser> result = new ArrayList<>();
        userRepository.findAll().forEach(user -> {
            if (!user.getId().equals(callerId)) {
                result.add(new ShareableUser(user.getId(), user.getName()));
            }
        });
        return result;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    @Transactional
    public PlaybackSharingSettings updatePlaybackSharingSettings(@Argument PlaybackSharingSettingsInput input,
                                                                 Authentication authentication) {
        UserEntity user = userService.getOrCreateUser(authentication);
        UserSharingSettingsEntity settings = settingsRepository.findByUserEntityId(user.getId())
                .orElseGet(() -> UserSharingSettingsEntity.builder().userEntity(user).build());
        settings.setNowPlayingScope(input.nowPlayingScope());
        settings.setControlScope(input.controlScope());
        settingsRepository.save(settings);

        rewriteAccountGrants(user, SharingCapability.VIEW, input.nowPlayingAllowedUserIds());
        rewriteAccountGrants(user, SharingCapability.CONTROL, input.controlAllowedUserIds());

        playbackSharingService.invalidateCache();
        return toDto(playbackSharingService.forOwner(user.getId()));
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    @Transactional
    public boolean setSessionSharing(@Argument UUID playQueueId, @Argument RemoteControlScope controlScope,
                                     @Argument List<UUID> allowedUserIds, Authentication authentication) {
        UserEntity user = userService.getOrCreateUser(authentication);
        PlayQueueEntity queue = playQueueRepository.findById(playQueueId)
                .orElseThrow(() -> new IllegalArgumentException("Play queue not found"));
        if (!queue.getUserEntity().getId().equals(user.getId())) {
            throw new AccessDeniedException("Play queue does not belong to the authenticated user");
        }
        queue.setControlScopeOverride(controlScope);
        playQueueRepository.save(queue);

        // The session's own control allowlist only applies to an ALLOWLIST override; clear it otherwise.
        playQueueControlGrantRepository.deleteByPlayQueueEntityId(playQueueId);
        if (controlScope == RemoteControlScope.ALLOWLIST && allowedUserIds != null) {
            for (UUID granteeId : new LinkedHashSet<>(allowedUserIds)) {
                if (granteeId.equals(user.getId())) {
                    continue; // the owner controls their own session unconditionally
                }
                userRepository.findById(granteeId).ifPresent(grantee ->
                        playQueueControlGrantRepository.save(PlayQueueControlGrantEntity.builder()
                                .playQueueEntity(queue).granteeEntity(grantee).build()));
            }
        }
        return true;
    }

    private void rewriteAccountGrants(UserEntity owner, SharingCapability capability, List<UUID> granteeIds) {
        grantRepository.deleteByOwnerEntityIdAndCapability(owner.getId(), capability);
        if (granteeIds == null) {
            return;
        }
        for (UUID granteeId : new LinkedHashSet<>(granteeIds)) {
            if (granteeId.equals(owner.getId())) {
                continue; // no self-grants; the owner always sees/controls their own sessions
            }
            userRepository.findById(granteeId).ifPresent(grantee ->
                    grantRepository.save(UserSharingGrantEntity.builder()
                            .ownerEntity(owner).granteeEntity(grantee).capability(capability).build()));
        }
    }

    private static PlaybackSharingSettings toDto(OwnerSharing sharing) {
        return new PlaybackSharingSettings(sharing.nowPlayingScope(), sharing.controlScope(),
                new ArrayList<>(sharing.viewAllowed()), new ArrayList<>(sharing.controlAllowed()));
    }
}
