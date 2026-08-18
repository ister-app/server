package app.ister.core.entity;

import app.ister.core.enums.StreamCodecType;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"mediaFileEntityId", "streamIndex", "path"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class MediaFileStreamEntity extends BaseEntity {

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = false)
    private MediaFileEntity mediaFileEntity;

    private int streamIndex;

    @Column(nullable = false)
    private String codecName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StreamCodecType codecType;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(nullable = false)
    private String path;

    // https://en.wikipedia.org/wiki/ISO_639-3
    private String language;

    private String title;

    /**
     * Baked-in black-bar crop rect (source pixels), detected with ffmpeg
     * cropdetect after the stream rows are built — hence the setters. Null =
     * never detected; equal to the full frame = detected, no bars.
     */
    // Explicit column names: Hibernate's camel-to-underscores strategy only
    // splits before an uppercase that is followed by a lowercase, so cropX
    // would map to "cropx" instead of "crop_x".
    @Setter
    @Column(name = "crop_x")
    private Integer cropX;
    @Setter
    @Column(name = "crop_y")
    private Integer cropY;
    @Setter
    @Column(name = "crop_width")
    private Integer cropWidth;
    @Setter
    @Column(name = "crop_height")
    private Integer cropHeight;

    /**
     * True when extracting this subtitle stream to SRT was attempted during analysis
     * but failed (OCR error, no usable OCR language, ffmpeg failure). Null = never
     * attempted or not applicable. The scanner's re-extract backfill skips streams
     * marked true, so a permanently failing extraction does not re-trigger a full
     * re-analysis on every scan; a re-analysis rewrites the rows and retries anyway.
     */
    @Setter
    private Boolean extractionFailed;
}
