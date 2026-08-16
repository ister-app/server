package app.ister.api.controller;

import app.ister.core.service.UserSettingsService;
import app.ister.core.service.UserSettingsService.UserSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.GraphQlTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Schema-wiring test for the user settings, in particular the autoSkipIntro round-trip. */
@GraphQlTest(UserSettingsController.class)
class UserSettingsControllerGraphQlTest {

    @Autowired
    private GraphQlTester graphQlTester;

    @MockitoBean
    private UserSettingsService userSettingsService;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user-1", "n/a", "ROLE_user"));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userSettingsExposesAutoSkipIntro() {
        when(userSettingsService.get(any())).thenReturn(
                new UserSettings(List.of("en"), List.of(), true, true, null, true));

        Boolean autoSkipIntro = graphQlTester
                .document("{ userSettings { directPlay autoSkipIntro } }")
                .execute()
                .path("userSettings.autoSkipIntro").entity(Boolean.class).get();

        assertTrue(autoSkipIntro);
    }

    @Test
    void updateUserSettingsRoundTripsAutoSkipIntro() {
        when(userSettingsService.update(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        graphQlTester.document("""
                        mutation {
                          updateUserSettings(input: {
                            preferredAudioLanguages: ["nl"], preferredSubtitleLanguages: [],
                            directPlay: true, transcode: true, autoSkipIntro: true
                          }) { autoSkipIntro }
                        }""")
                .execute()
                .path("updateUserSettings.autoSkipIntro").entity(Boolean.class).isEqualTo(true);

        ArgumentCaptor<UserSettings> captor = ArgumentCaptor.forClass(UserSettings.class);
        verify(userSettingsService).update(any(), captor.capture());
        assertTrue(captor.getValue().autoSkipIntro());
    }

    @Test
    void autoSkipIntroDefaultsToFalseForClientsPredatingTheField() {
        when(userSettingsService.update(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        graphQlTester.document("""
                        mutation {
                          updateUserSettings(input: {
                            preferredAudioLanguages: [], preferredSubtitleLanguages: [],
                            directPlay: true, transcode: true
                          }) { autoSkipIntro }
                        }""")
                .execute()
                .path("updateUserSettings.autoSkipIntro").entity(Boolean.class).isEqualTo(false);

        ArgumentCaptor<UserSettings> captor = ArgumentCaptor.forClass(UserSettings.class);
        verify(userSettingsService).update(any(), captor.capture());
        assertFalse(captor.getValue().autoSkipIntro());
        assertEquals(List.of(), captor.getValue().preferredAudioLanguages());
    }
}
