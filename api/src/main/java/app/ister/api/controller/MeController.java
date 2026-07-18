package app.ister.api.controller;

import app.ister.core.entity.UserEntity;
import app.ister.core.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class MeController {
    private final UserService userService;

    public record Me(UUID id, String name, String email, boolean isAdmin) {
    }

    /** isAdmin comes from the token's authorities — the JWT is authoritative, not the DB snapshot. */
    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Me me(Authentication authentication) {
        UserEntity user = userService.getOrCreateUser(authentication);
        return new Me(user.getId(), user.getName(), user.getEmail(), UserService.hasAdminRole(authentication));
    }
}
