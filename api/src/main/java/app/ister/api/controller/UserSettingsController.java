package app.ister.api.controller;

import app.ister.api.dto.UserSettingsInput;
import app.ister.core.service.UserSettingsService;
import app.ister.core.service.UserSettingsService.UserSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

/**
 * The calling user's playback settings. They live on the server rather than in each client's local
 * storage so they follow the account across devices, and so pre-transcoding can restrict itself to
 * the audio languages and video variants this user will actually play.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class UserSettingsController {

    private final UserSettingsService userSettingsService;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public UserSettings userSettings(Authentication authentication) {
        return userSettingsService.get(authentication);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public UserSettings updateUserSettings(@Argument UserSettingsInput input, Authentication authentication) {
        return userSettingsService.update(authentication, new UserSettings(
                input.preferredAudioLanguages(), input.preferredSubtitleLanguages(),
                input.directPlay(), input.transcode(), input.maxVideoHeight()));
    }
}
