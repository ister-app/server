package app.ister.server.service;

import app.ister.server.entitiy.UserEntity;
import app.ister.server.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
public class UserService {
    @Autowired
    private UserRepository userRepository;

    public UserEntity getOrCreateUser(Authentication authentication) {
        Optional<UserEntity> user = userRepository.findByExternalId(authentication.getName());
        if (user.isPresent()) {
            return user.get();
        } else {
            Jwt principal = (Jwt) authentication.getPrincipal();
            UserEntity userEntity = UserEntity.builder()
                    .externalId(authentication.getName())
                    .name(principal.getClaim("name"))
                    .email(principal.getClaim("email")).build();
            userRepository.save(userEntity);
            return userEntity;
        }
    }
}
