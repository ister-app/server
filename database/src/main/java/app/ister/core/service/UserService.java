package app.ister.core.service;

import app.ister.core.entity.UserEntity;
import app.ister.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    public UserEntity getOrCreateUser(Authentication authentication) {
        Optional<UserEntity> user = userRepository.findByExternalId(authentication.getName());
        if (user.isPresent()) {
            UserEntity userEntity = user.get();
            snapshotAdminRole(authentication, userEntity);
            return userEntity;
        } else {
            Jwt principal = (Jwt) authentication.getPrincipal();
            UserEntity userEntity = UserEntity.builder()
                    .externalId(authentication.getName())
                    .name(principal.getClaim("name"))
                    .email(principal.getClaim("email"))
                    .admin(hasAdminRole(authentication)).build();
            userRepository.save(userEntity);
            return userEntity;
        }
    }

    /**
     * Keeps the DB admin flag in sync with the Keycloak realm role. Only JWT-authenticated
     * requests carry the roles claim; stream-token requests authenticate with a fixed
     * ROLE_user and must not overwrite the snapshot.
     */
    private void snapshotAdminRole(Authentication authentication, UserEntity userEntity) {
        if (authentication.getPrincipal() instanceof Jwt && userEntity.isAdmin() != hasAdminRole(authentication)) {
            userEntity.setAdmin(hasAdminRole(authentication));
            userRepository.save(userEntity);
        }
    }

    public static boolean hasAdminRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_admin".equals(authority.getAuthority()));
    }
}
