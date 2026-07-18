package app.ister.core.service;

import app.ister.core.entity.SeriesEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.UserSeriesPreferenceEntity;
import app.ister.core.enums.ReadingDirection;
import app.ister.core.repository.SeriesRepository;
import app.ister.core.repository.UserSeriesPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores each user's per-series reading direction override. Kept server-side so the choice applies
 * to every client the user has. A series the user never touched has no row and falls back to the
 * series' detected default (RTL for manga), else LTR.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SeriesPreferenceService {
    /** What an unset preference on a series without a detected default means. */
    public static final ReadingDirection FALLBACK_DIRECTION = ReadingDirection.LTR;

    private final UserService userService;
    private final UserSeriesPreferenceRepository userSeriesPreferenceRepository;
    private final SeriesRepository seriesRepository;

    /** The caller's effective reading direction for this series: override, else series default, else LTR. */
    @Transactional(readOnly = true)
    public ReadingDirection getReadingDirection(Authentication authentication, UUID seriesId) {
        SeriesEntity seriesEntity = series(seriesId);
        UserEntity userEntity = userService.getOrCreateUser(authentication);
        return resolve(userSeriesPreferenceRepository.findByUserEntityAndSeriesEntity(userEntity, seriesEntity)
                .map(UserSeriesPreferenceEntity::getReadingDirection)
                .orElse(null), seriesEntity);
    }

    /** Override, else series default, else LTR — shared with the GraphQL batch resolver. */
    public static ReadingDirection resolve(ReadingDirection preference, SeriesEntity seriesEntity) {
        if (preference != null) {
            return preference;
        }
        ReadingDirection detected = seriesEntity.getDefaultReadingDirection();
        return detected != null ? detected : FALLBACK_DIRECTION;
    }

    /**
     * Sets the caller's reading direction for this series; null clears the override so the series
     * default applies again.
     *
     * @throws NoSuchElementException if the series does not exist
     */
    @Transactional
    public void setReadingDirection(Authentication authentication, UUID seriesId, ReadingDirection direction) {
        UserEntity userEntity = userService.getOrCreateUser(authentication);
        SeriesEntity seriesEntity = series(seriesId);
        Optional<UserSeriesPreferenceEntity> existing =
                userSeriesPreferenceRepository.findByUserEntityAndSeriesEntity(userEntity, seriesEntity);

        if (direction == null) {
            existing.ifPresent(userSeriesPreferenceRepository::delete);
        } else if (existing.isPresent()) {
            UserSeriesPreferenceEntity preference = existing.get();
            preference.setReadingDirection(direction);
            userSeriesPreferenceRepository.save(preference);
        } else {
            userSeriesPreferenceRepository.save(UserSeriesPreferenceEntity.builder()
                    .userEntity(userEntity)
                    .seriesEntity(seriesEntity)
                    .readingDirection(direction)
                    .build());
        }
    }

    private SeriesEntity series(UUID id) {
        return seriesRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Series not found: " + id));
    }
}
