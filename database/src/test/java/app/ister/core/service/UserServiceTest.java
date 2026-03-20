package app.ister.core.service;

import app.ister.core.entity.UserEntity;
import app.ister.core.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @InjectMocks
    private UserService subject;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Authentication authentication;

    @Mock
    private Jwt jwt;

    @Test
    void getOrCreateUserReturnsExisting() {
        UserEntity existing = UserEntity.builder().externalId("user-123").build();

        when(authentication.getName()).thenReturn("user-123");
        when(userRepository.findByExternalId("user-123")).thenReturn(Optional.of(existing));

        UserEntity result = subject.getOrCreateUser(authentication);

        assertEquals(existing, result);
    }

    @Test
    void getOrCreateUserCreatesNew() {
        when(authentication.getName()).thenReturn("user-456");
        when(authentication.getPrincipal()).thenReturn(jwt);
        when(jwt.getClaim("name")).thenReturn("Test User");
        when(jwt.getClaim("email")).thenReturn("test@example.com");
        when(userRepository.findByExternalId("user-456")).thenReturn(Optional.empty());

        UserEntity result = subject.getOrCreateUser(authentication);

        assertEquals("user-456", result.getExternalId());
        assertEquals("Test User", result.getName());
        assertEquals("test@example.com", result.getEmail());
        verify(userRepository).save(any(UserEntity.class));
    }
}
