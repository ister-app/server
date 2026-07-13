package app.ister.core.service;

import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserSettingsEntity;
import app.ister.core.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Each user's playback settings. Stored server-side (not only on the client) so they follow the
 * account to every device, and so pre-transcoding knows which audio tracks a user will actually
 * play — without that, a file with seven audio streams gets every one of them transcoded.
 *
 * <p>A user who never saved settings falls back to the app-wide languages
 * ({@code app.ister.languages}) with direct play and transcoding on and no quality cap.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserSettingsService {

    private final UserService userService;
    private final UserSettingsRepository userSettingsRepository;

    /**
     * The app-wide languages a user without saved settings falls back to. Read as a property rather
     * than through {@code LanguageProperties}: that class lives in core, which depends on this module.
     */
    @Value("${app.ister.languages:en,nl}")
    private List<String> defaultLanguages;

    /**
     * A user's settings, as a value object: the pre-transcode paths read these off a RabbitMQ
     * listener thread, where a detached entity's lazy user reference would blow up.
     *
     * @param maxVideoHeight highest video variant to pre-transcode, or null for every variant
     */
    public record UserSettings(List<String> preferredAudioLanguages, List<String> preferredSubtitleLanguages,
                               boolean directPlay, boolean transcode, Integer maxVideoHeight) {
    }

    /** The caller's settings, or the defaults when they never saved any. */
    @Transactional(readOnly = true)
    public UserSettings get(Authentication authentication) {
        UserEntity user = userService.getOrCreateUser(authentication);
        return forUser(user.getId());
    }

    /** The settings of the given user, or the defaults when they never saved any. */
    @Transactional(readOnly = true)
    public UserSettings forUser(UUID userId) {
        return userSettingsRepository.findByUserEntityId(userId)
                .map(UserSettingsService::toSettings)
                .orElseGet(this::defaults);
    }

    /** Settings for a user without a row: the app-wide languages, everything else unrestricted. */
    public UserSettings defaults() {
        return new UserSettings(defaultLanguages, defaultLanguages, true, true, null);
    }

    @Transactional
    public UserSettings update(Authentication authentication, UserSettings settings) {
        UserEntity user = userService.getOrCreateUser(authentication);
        UserSettingsEntity entity = userSettingsRepository.findByUserEntity(user)
                .orElseGet(() -> UserSettingsEntity.builder().userEntity(user).build());

        entity.setPreferredAudioLanguages(List.copyOf(settings.preferredAudioLanguages()));
        entity.setPreferredSubtitleLanguages(List.copyOf(settings.preferredSubtitleLanguages()));
        entity.setDirectPlay(settings.directPlay());
        entity.setTranscode(settings.transcode());
        entity.setMaxVideoHeight(settings.maxVideoHeight());

        return toSettings(userSettingsRepository.save(entity));
    }

    private static UserSettings toSettings(UserSettingsEntity entity) {
        return new UserSettings(entity.getPreferredAudioLanguages(), entity.getPreferredSubtitleLanguages(),
                entity.isDirectPlay(), entity.isTranscode(), entity.getMaxVideoHeight());
    }
}
