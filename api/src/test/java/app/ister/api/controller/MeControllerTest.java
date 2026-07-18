package app.ister.api.controller;

import app.ister.core.entity.UserEntity;
import app.ister.core.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeControllerTest {

    @InjectMocks
    private MeController subject;

    @Mock
    private UserService userService;

    @Mock
    private Authentication authentication;

    private static UserEntity user(UUID id) {
        UserEntity user = UserEntity.builder()
                .externalId("user-123")
                .name("Test User")
                .email("test@example.com")
                .build();
        user.setId(id);
        return user;
    }

    @Test
    void meIsAdminWithAdminAuthority() {
        UUID id = UUID.randomUUID();
        when(userService.getOrCreateUser(authentication)).thenReturn(user(id));
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_admin"))).when(authentication).getAuthorities();

        MeController.Me result = subject.me(authentication);

        assertEquals(id, result.id());
        assertEquals("Test User", result.name());
        assertEquals("test@example.com", result.email());
        assertTrue(result.isAdmin());
    }

    @Test
    void meIsNotAdminWithUserAuthorityOnly() {
        UUID id = UUID.randomUUID();
        when(userService.getOrCreateUser(authentication)).thenReturn(user(id));
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_user"))).when(authentication).getAuthorities();

        MeController.Me result = subject.me(authentication);

        assertEquals(id, result.id());
        assertEquals("Test User", result.name());
        assertEquals("test@example.com", result.email());
        assertFalse(result.isAdmin());
    }
}
