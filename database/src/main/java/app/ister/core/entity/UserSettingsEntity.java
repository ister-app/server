package app.ister.core.entity;

import app.ister.core.entity.converter.StringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * A user's playback settings, kept server-side so they follow the account to every device — and so
 * pre-transcoding knows which audio tracks are worth producing. At most one row per user (unique
 * index in Flyway); a user without a row falls back to the app-wide {@code app.ister.languages}.
 *
 * <p>The language lists are ordered: first match wins, exactly as the player applies them.
 */
@Entity
@Table(name = "user_settings")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UserSettingsEntity extends BaseEntity {

    @OneToOne(optional = false)
    private UserEntity userEntity;

    /** Preferred spoken languages, most preferred first (ISO-639-1 or ISO-639-3 tags). */
    @Convert(converter = StringListConverter.class)
    @Column(nullable = false)
    private List<String> preferredAudioLanguages;

    /** Preferred subtitle languages, most preferred first. Empty means: no subtitles by default. */
    @Convert(converter = StringListConverter.class)
    @Column(nullable = false)
    private List<String> preferredSubtitleLanguages;

    @Column(nullable = false)
    private boolean directPlay;

    @Column(nullable = false)
    private boolean transcode;

    /** Highest video variant to pre-transcode (720 / 480); null means every variant. */
    private Integer maxVideoHeight;
}
