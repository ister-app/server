package app.ister.core.service;

import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserSettingsEntity;
import app.ister.core.repository.UserSettingsRepository;
import app.ister.core.service.UserSettingsService.UserSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSettingsServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @InjectMocks
    private UserSettingsService subject;

    @Mock private UserService userService;
    @Mock private UserSettingsRepository userSettingsRepository;
    @Mock private Authentication authentication;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(subject, "defaultLanguages", List.of("en", "nl"));
        user = UserEntity.builder().externalId("external-1").build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
    }

    @Test
    void userWithoutSettingsFallsBackToTheAppLanguages() {
        when(userSettingsRepository.findByUserEntityId(USER_ID)).thenReturn(Optional.empty());

        UserSettings settings = subject.forUser(USER_ID);

        assertEquals(List.of("en", "nl"), settings.preferredAudioLanguages());
        assertEquals(List.of("en", "nl"), settings.preferredSubtitleLanguages());
        assertTrue(settings.directPlay());
        assertTrue(settings.transcode());
        assertNull(settings.maxVideoHeight(), "no quality cap without saved settings");
    }

    @Test
    void savedSettingsAreReturned() {
        when(userSettingsRepository.findByUserEntityId(USER_ID)).thenReturn(Optional.of(
                UserSettingsEntity.builder()
                        .userEntity(user)
                        .preferredAudioLanguages(List.of("nl"))
                        .preferredSubtitleLanguages(List.of("en", "nl"))
                        .directPlay(false)
                        .transcode(true)
                        .maxVideoHeight(480)
                        .build()));

        UserSettings settings = subject.forUser(USER_ID);

        assertEquals(List.of("nl"), settings.preferredAudioLanguages());
        assertEquals(List.of("en", "nl"), settings.preferredSubtitleLanguages());
        assertEquals(480, settings.maxVideoHeight());
    }

    @Test
    void updateCreatesTheRowOnFirstSaveAndKeepsLanguageOrder() {
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(userSettingsRepository.findByUserEntity(user)).thenReturn(Optional.empty());
        when(userSettingsRepository.save(any(UserSettingsEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserSettings saved = subject.update(authentication,
                new UserSettings(List.of("nl", "en"), List.of("nl"), true, true, 720));

        ArgumentCaptor<UserSettingsEntity> captor = ArgumentCaptor.forClass(UserSettingsEntity.class);
        verify(userSettingsRepository).save(captor.capture());
        assertEquals(user, captor.getValue().getUserEntity());
        // Order is meaningful: the player plays the first language it finds a track for.
        assertEquals(List.of("nl", "en"), captor.getValue().getPreferredAudioLanguages());
        assertEquals(List.of("nl", "en"), saved.preferredAudioLanguages());
        assertEquals(720, saved.maxVideoHeight());
    }

    @Test
    void updateOverwritesAnExistingRow() {
        UserSettingsEntity existing = UserSettingsEntity.builder()
                .userEntity(user)
                .preferredAudioLanguages(List.of("en"))
                .preferredSubtitleLanguages(List.of())
                .directPlay(true)
                .transcode(true)
                .build();
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(userSettingsRepository.findByUserEntity(user)).thenReturn(Optional.of(existing));
        when(userSettingsRepository.save(any(UserSettingsEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        subject.update(authentication, new UserSettings(List.of("nl"), List.of("nl"), false, true, null));

        assertEquals(List.of("nl"), existing.getPreferredAudioLanguages());
        assertEquals(List.of("nl"), existing.getPreferredSubtitleLanguages());
        assertEquals(false, existing.isDirectPlay());
        assertNull(existing.getMaxVideoHeight());
    }
}
