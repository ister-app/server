package app.ister.core.service;

import app.ister.core.entity.SeriesEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserSeriesPreferenceEntity;
import app.ister.core.enums.ReadingDirection;
import app.ister.core.repository.SeriesRepository;
import app.ister.core.repository.UserSeriesPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeriesPreferenceServiceTest {

    @InjectMocks
    private SeriesPreferenceService subject;

    @Mock
    private UserService userService;

    @Mock
    private UserSeriesPreferenceRepository userSeriesPreferenceRepository;

    @Mock
    private SeriesRepository seriesRepository;

    @Mock
    private Authentication authentication;

    private UserEntity user;
    private SeriesEntity series;

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().id(UUID.randomUUID()).externalId("user-1").build();
        series = SeriesEntity.builder().id(UUID.randomUUID()).name("Attack on Titan").build();
    }

    private void mockUserAndSeries() {
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(seriesRepository.findById(series.getId())).thenReturn(Optional.of(series));
    }

    @Test
    void directionDefaultsToLtrWithoutPreferenceOrDetectedDefault() {
        mockUserAndSeries();
        when(userSeriesPreferenceRepository.findByUserEntityAndSeriesEntity(user, series))
                .thenReturn(Optional.empty());

        assertEquals(ReadingDirection.LTR, subject.getReadingDirection(authentication, series.getId()));
    }

    /** A manga's detected default applies when the user never chose. */
    @Test
    void directionFallsBackToTheSeriesDefault() {
        series.setDefaultReadingDirection(ReadingDirection.RTL);
        mockUserAndSeries();
        when(userSeriesPreferenceRepository.findByUserEntityAndSeriesEntity(user, series))
                .thenReturn(Optional.empty());

        assertEquals(ReadingDirection.RTL, subject.getReadingDirection(authentication, series.getId()));
    }

    @Test
    void aStoredPreferenceWinsOverTheSeriesDefault() {
        series.setDefaultReadingDirection(ReadingDirection.RTL);
        mockUserAndSeries();
        when(userSeriesPreferenceRepository.findByUserEntityAndSeriesEntity(user, series))
                .thenReturn(Optional.of(UserSeriesPreferenceEntity.builder()
                        .userEntity(user).seriesEntity(series)
                        .readingDirection(ReadingDirection.LTR).build()));

        assertEquals(ReadingDirection.LTR, subject.getReadingDirection(authentication, series.getId()));
    }

    @Test
    void setDirectionInsertsARowWhenThereIsNone() {
        mockUserAndSeries();
        when(userSeriesPreferenceRepository.findByUserEntityAndSeriesEntity(user, series))
                .thenReturn(Optional.empty());

        subject.setReadingDirection(authentication, series.getId(), ReadingDirection.RTL);

        ArgumentCaptor<UserSeriesPreferenceEntity> saved =
                ArgumentCaptor.forClass(UserSeriesPreferenceEntity.class);
        verify(userSeriesPreferenceRepository).save(saved.capture());
        assertEquals(user, saved.getValue().getUserEntity());
        assertEquals(series, saved.getValue().getSeriesEntity());
        assertEquals(ReadingDirection.RTL, saved.getValue().getReadingDirection());
    }

    @Test
    void setDirectionUpdatesTheExistingRow() {
        mockUserAndSeries();
        UserSeriesPreferenceEntity existing = UserSeriesPreferenceEntity.builder()
                .userEntity(user).seriesEntity(series)
                .readingDirection(ReadingDirection.RTL).build();
        when(userSeriesPreferenceRepository.findByUserEntityAndSeriesEntity(user, series))
                .thenReturn(Optional.of(existing));

        subject.setReadingDirection(authentication, series.getId(), ReadingDirection.LTR);

        assertEquals(ReadingDirection.LTR, existing.getReadingDirection());
        verify(userSeriesPreferenceRepository).save(existing);
    }

    /** Null clears the override: the row is deleted so the series default applies again. */
    @Test
    void setDirectionNullDeletesTheRow() {
        mockUserAndSeries();
        UserSeriesPreferenceEntity existing = UserSeriesPreferenceEntity.builder()
                .userEntity(user).seriesEntity(series)
                .readingDirection(ReadingDirection.RTL).build();
        when(userSeriesPreferenceRepository.findByUserEntityAndSeriesEntity(user, series))
                .thenReturn(Optional.of(existing));

        subject.setReadingDirection(authentication, series.getId(), null);

        verify(userSeriesPreferenceRepository).delete(existing);
        verify(userSeriesPreferenceRepository, never()).save(any());
    }

    @Test
    void setDirectionNullWithoutARowIsANoOp() {
        mockUserAndSeries();
        when(userSeriesPreferenceRepository.findByUserEntityAndSeriesEntity(user, series))
                .thenReturn(Optional.empty());

        subject.setReadingDirection(authentication, series.getId(), null);

        verify(userSeriesPreferenceRepository, never()).delete(any());
        verify(userSeriesPreferenceRepository, never()).save(any());
    }

    @Test
    void setDirectionRejectsAnUnknownSeries() {
        UUID unknownId = UUID.randomUUID();
        when(userService.getOrCreateUser(authentication)).thenReturn(user);
        when(seriesRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> subject.setReadingDirection(authentication, unknownId, ReadingDirection.RTL));
        verify(userSeriesPreferenceRepository, never()).save(any());
    }
}
