package app.ister.api.controller;

import app.ister.core.entity.StreamTokenEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.service.StreamTokenService;
import app.ister.core.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class StreamTokenController {

    private final StreamTokenService streamTokenService;
    private final UserService userService;

    @MutationMapping
    @PreAuthorize("hasRole('user')")
    public StreamTokenEntity createStreamToken(Authentication authentication) {
        UserEntity user = userService.getOrCreateUser(authentication);
        return streamTokenService.createToken(user);
    }
}
